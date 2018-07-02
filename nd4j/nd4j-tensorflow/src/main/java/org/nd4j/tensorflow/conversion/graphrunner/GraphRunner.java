package org.nd4j.tensorflow.conversion.graphrunner;

import com.github.os72.protobuf351.InvalidProtocolBufferException;
import lombok.Getter;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.tensorflow;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.tensorflow.conversion.TensorflowConversion;
import org.tensorflow.framework.NodeDef;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

import static org.bytedeco.javacpp.tensorflow.*;

/**
 * Runs a tensorflow session based on zero copy
 * {@link INDArray}
 *
 * @author Adam Gibson
 */
public class GraphRunner implements Closeable {

    private byte[] graphToUse;
    private tensorflow.TF_Graph graph;
    private TensorflowConversion conversion = new TensorflowConversion();
    private tensorflow.TF_Session session;
    private tensorflow.TF_SessionOptions options;
    private tensorflow.TF_Status status;
    @Getter
    private Set<String> inputsForGraph,outputsForGraph;
    private List<String> inputOrder,outputOrder;
    /**
     * Initialize with the graph content to use
     * @param graphToUse the raw byte content
     *                   of a protobuf file saved by tensorflow
     */
    public GraphRunner(byte[] graphToUse) {
        this.graphToUse = graphToUse;
        initSessionAndStatusIfNeeded();
    }


    /**
     * Returns a map of the output names
     * to the ndarrays matching each output.
     *
     * Note that {@link IllegalArgumentException}
     * will be thrown if there are any invalid states such as:
     * the graph being null
     *
     *
     * the inputs resolved from the graph do not match
     * the inputs passed in
     *
     *
     *
     * @param inputs the inputs to use for each
     *               {@link INDArray}
     * @return a map of the output names to the
     * ndarrays matching each output specified in the graph
     * @throws IOException
     */
    public Map<String,INDArray> run(Map<String,INDArray> inputs) {
        if(graph == null) {
            throw new IllegalStateException("Graph not initialized.");
        }



        if(inputOrder.size() != inputsForGraph.size()) {
            throw new IllegalArgumentException("Input order specified does not match inferred inputs from graph definition. Missing inputs?");
        }

        if(inputs.size() != inputOrder.size()) {
            throw new IllegalArgumentException("Number of inputs specified do not match number of arrays specified.");
        }


        Map<String,INDArray> outputArrays = new LinkedHashMap<>();

        Map<String,TF_Operation> opsByName = new HashMap<>();
        tensorflow.TF_Output inputOut = new tensorflow.TF_Output(inputOrder.size());

        TF_Tensor[] inputTensors = new TF_Tensor[inputOrder.size()];
        for(int i = 0; i < inputOrder.size(); i++) {
            tensorflow.TF_Operation inputOp = TF_GraphOperationByName(graph, inputOrder.get(i));
            opsByName.put(inputOrder.get(i),inputOp);
            inputOut.position(i).oper(inputOp).index(0);
            TF_Tensor tf_tensor = conversion.tensorFromNDArray(inputs.get(inputOrder.get(i)));
            inputTensors[i] = tf_tensor;
        }


        //reset the position of the pointer for execution
        inputOut.position(0);

        TF_Output outputOut = new tensorflow.TF_Output(outputOrder.size());
        for(int i = 0; i < outputOrder.size(); i++) {
            tensorflow.TF_Operation outputOp = TF_GraphOperationByName(graph, outputOrder.get(i));
            opsByName.put(outputOrder.get(i),outputOp);
            outputOut.position(i).oper(outputOp).position(i).index(0);
        }

        //reset the position of the pointer for execution
        outputOut.position(0);



        PointerPointer<TF_Tensor> inputTensorsPointer = new PointerPointer<>(inputTensors);
        PointerPointer<TF_Tensor> outputTensorsPointer = new PointerPointer<>(outputOrder.size());


        TF_SessionRun(
                session,
                null,
                inputOut, inputTensorsPointer, inputTensors.length,
                outputOut, outputTensorsPointer, outputOrder.size(),
                null
                , 0,
                null,
                status);


        if (TF_GetCode(status) != TF_OK) {
            throw new RuntimeException("ERROR: Unable to run session " + TF_Message(status).getString());
        } else {
            for(int i = 0; i < outputOrder.size(); i++) {
                INDArray to = conversion.ndArrayFromTensor(new TF_Tensor(outputTensorsPointer.get(i)));
                outputArrays.put(outputOrder.get(i),to);
            }

        }

        return outputArrays;
    }

    private void initSessionAndStatusIfNeeded() {
        try {
            org.tensorflow.framework.GraphDef graphDef1 = org.tensorflow.framework.GraphDef.parseFrom(graphToUse);
            inputsForGraph = new LinkedHashSet<>();
            outputsForGraph = new LinkedHashSet<>();
            Set<String> seenAsInput = new LinkedHashSet<>();
            for(int i = 0; i < graphDef1.getNodeCount(); i++) {
                NodeDef node = graphDef1.getNode(i);
                if(node.getInputCount() < 1) {
                    inputsForGraph.add(node.getName());
                }

                for(int input = 0; input < node.getInputCount(); input++) {
                    seenAsInput.add(node.getInput(input));
                }
            }

            for(int i = 0; i < graphDef1.getNodeCount(); i++) {
                if(!seenAsInput.contains(graphDef1.getNode(i).getName())) {
                    outputsForGraph.add(graphDef1.getNode(i).getName());
                }
            }

            inputOrder = new ArrayList<>(inputsForGraph);
            outputOrder = new ArrayList<>(outputsForGraph);

        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }


        if(status == null) {
            status = TF_NewStatus();
        }


        if(session == null) {
            try {
                graph = conversion.getInitializedGraphForNd4jDevices(graphToUse);
            } catch (IOException e) {
                e.printStackTrace();
            }

            options = TF_NewSessionOptions();
            session = tensorflow.TF_NewSession(graph, options, status);
            if (TF_GetCode(status) != TF_OK) {
                throw new RuntimeException("ERROR: Unable to open session " + TF_Message(status).getString());
            }

        }

    }





    @Override
    public void close() throws IOException {
        if(session != null && status != null) {
            TF_CloseSession(session, status);
            TF_DeleteSession(session,status);
        }

        if(status != null && TF_GetCode(status) != TF_OK) {
            throw new RuntimeException("ERROR: Unable to delete session " + TF_Message(status).getString());
        }



        if(status != null) {
            TF_DeleteStatus(status);
        }
    }
}
