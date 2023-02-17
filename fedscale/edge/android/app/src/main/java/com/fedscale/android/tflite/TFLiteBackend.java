package com.fedscale.android.tflite;

import static java.lang.Math.min;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.Pair;

import com.fedscale.android.utils.Backend;
import com.fedscale.android.utils.Common;
import com.fedscale.android.utils.ImagePreprocessor;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TFLiteBackend implements Backend {
    /**
     * @param directory        Directory of model and data.
     * @param model            Model filename.
     * @param trainingDataConf Training set and label path config.
     * @param trainingConf     JSON config for training.
     * @return Training result containing updated model in JSON.
     */
    @Override
    public Map<String, Object> MLTrain(
            String directory,
            String model,
            JSONObject trainingDataConf,
            JSONObject trainingConf) throws JSONException, IOException {
        final String trainImagesFolder = directory + "/" + trainingDataConf.getString("data" ) + "/";
        final String trainImagesTxt = directory + "/" + trainingDataConf.getString("label");

        final String clientId       = trainingConf.getString("client_id");
        final int numClasses        = trainingConf.getInt("num_classes");
        final int trainEpochs       = trainingConf.getInt("epoch");
        final int trainBatchSize    = trainingConf.getInt("batch_size");
        final int trainNumWorkers   = trainingConf.getInt("num_workers");
        final float learningRate    = (float) trainingConf.getDouble("learning_rate");
        final float lossDecay       = (float) trainingConf.getDouble("loss_decay");
        final float momentum        = (float) trainingConf.getDouble("momentum");
        final float weightDecay     = (float) trainingConf.getDouble("weight_decay");
        final int channel           = trainingConf.getInt("channel");
        final int height            = trainingConf.getInt("height");
        final int width             = trainingConf.getInt("width");
        final int bottleneckSize    = trainingConf.getInt("bottleneck_size");

        List<String> labels = FileUtil.loadLabels(new FileInputStream(trainImagesTxt));
        final int dataCount = labels.size();
        final int trainIterations = (int)Math.ceil((double)dataCount / (double)trainBatchSize);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(trainNumWorkers);
        Interpreter interpreter = new Interpreter(new File(directory + "/" + model), options);

        List<Pair<float[], float[]>> dataset = dataLoader(
                labels,
                trainImagesFolder,
                this.getPreprocessor(interpreter, bottleneckSize),
                height,
                width,
                numClasses);

        float epochTrainLoss = 0;
        float currentLoss = 0;

        for (int epoch = 0; epoch < trainEpochs; ++epoch) {
            Collections.shuffle(dataset);
            Pair<List<float[][]>, List<float[][]>> shuffledDataset = generateBatchBuffers(
                    dataset, trainBatchSize, channel * height * width, numClasses);
            List<float[][]> trainImageBatches = shuffledDataset.first;
            List<float[][]> trainLabelBatches = shuffledDataset.second;
            for (int batchIdx = 0; batchIdx < trainIterations; ++batchIdx) {
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("data", trainImageBatches.get(batchIdx));
                inputs.put("label", trainLabelBatches.get(batchIdx));

                Map<String, Object> outputs = new HashMap<>();
                FloatBuffer loss = FloatBuffer.allocate(1);
                outputs.put("loss", loss);

                interpreter.runSignature(inputs, outputs, "train");
                currentLoss = loss.get(0);

                if (epochTrainLoss == 0) {
                    epochTrainLoss = currentLoss;
                } else {
                    epochTrainLoss = (1 - lossDecay) * epochTrainLoss +
                            lossDecay * currentLoss;
                }

                Log.i(
                        "Train",
                        String.format(
                                "[train][epoch][%d][batch][%d][loss][%f]",
                                epoch, batchIdx, currentLoss
                        )
                );
            }
        }
        Map<String, Object> inputs = new HashMap<>();
        File outputFile = new File(directory + "/model.ckpt");
        inputs.put("checkpoint_path", outputFile.getAbsolutePath());
        Map<String, Object> outputs = new HashMap<>();
        interpreter.runSignature(inputs, outputs, "save");

        Map<String, Object> results = new HashMap<>();
        results.put("client_id", clientId);
        results.put("moving_loss", epochTrainLoss);
        results.put("trained_size", trainEpochs * dataCount);
        results.put("success", true);
        byte[] newModel = Common.readFile(new FileInputStream(outputFile.getAbsolutePath())).toByteArray();
        results.put("update_weight", newModel);
        results.put("utility", currentLoss * currentLoss * dataCount);
        results.put("wall_duration", 0);
        return results;
    }

    /**
     * @param directory       Directory of model and data.
     * @param model           Model filename.
     * @param testingDataConf Testing set and label path config.
     * @param testingConf     JSON config for testing.
     * @return Testing result.
     */
    @Override
    public Map<String, Object> MLTest(
            String directory,
            String model,
            JSONObject testingDataConf,
            JSONObject testingConf) throws JSONException, IOException {
        final String testImagesFolder = directory + "/" + testingDataConf.getString("data" ) + "/";
        final String testImagesTxt = directory + "/" + testingDataConf.getString("label");

        final int numClasses        = testingConf.getInt("num_classes");
        final int testBatchSize     = testingConf.getInt("test_bsz");
        final int testNumWorkers    = testingConf.getInt("num_workers");
        final int channel           = testingConf.getInt("channel");
        final int height            = testingConf.getInt("height");
        final int width             = testingConf.getInt("width");
        final int bottleneckSize    = testingConf.getInt("bottleneck_size");

        List<String> labels = FileUtil.loadLabels(new FileInputStream(testImagesTxt));
        final int dataCount = labels.size();
        final int testIterations = (int)Math.ceil((double)dataCount / (double)testBatchSize);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(testNumWorkers);
        Interpreter interpreter = new Interpreter(new File(directory + "/" + model), options);


        List<Pair<float[], float[]>> dataset = dataLoader(
                labels,
                testImagesFolder,
                this.getPreprocessor(interpreter, bottleneckSize),
                height,
                width,
                numClasses);
        Pair<List<float[][]>, List<float[][]>> shuffledDataset = generateBatchBuffers(
                dataset, testBatchSize, channel * height * width, numClasses);
        List<float[][]> testImageBatches = shuffledDataset.first;
        List<float[][]> testLabelBatches = shuffledDataset.second;

        float testLoss = 0;
        int correctTop1 = 0;
        int correctTop5 = 0;
        int sampleCount = 0;

        for (int batchIdx = 0; batchIdx < testIterations; ++batchIdx) {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("data", testImageBatches.get(batchIdx));
            inputs.put("label", testLabelBatches.get(batchIdx));

            Map<String, Object> outputs = new HashMap<>();
            FloatBuffer loss = FloatBuffer.allocate(1);
            outputs.put("loss", loss);
            IntBuffer top1 = IntBuffer.allocate(1);
            outputs.put("top1", top1);
            IntBuffer top5 = IntBuffer.allocate(1);
            outputs.put("top5", top5);

            interpreter.runSignature(inputs, outputs, "test");

            testLoss += loss.get(0);
            correctTop1 += top1.get(0);
            correctTop5 += top5.get(0);
            sampleCount += testImageBatches.get(batchIdx).length;

            Log.i(
                    "Test",
                    String.format(
                            "[test][batch][%d][loss][%f][top 1][%d/%d=%f%%][top 5][%d/%d=%f%%]",
                            batchIdx, testLoss,
                            correctTop1, sampleCount, (float)correctTop1 / (float)sampleCount * 100,
                            correctTop5, sampleCount, (float)correctTop5 / (float)sampleCount * 100
                    )
            );
        }
        float accuracyFinalTop1 = (float)correctTop1 / dataCount;
        float accuracyFinalTop5 = (float)correctTop5 / dataCount;

        Map<String, Object> results = new HashMap<>();
        results.put("top_1", accuracyFinalTop1);
        results.put("top_5", accuracyFinalTop5);
        results.put("test_loss", testLoss);
        results.put("test_len", dataCount);
        return results;
    }

    /**
     * Generate image pre-processor.
     *
     * @param interpreter Interpreter of TFLite model.
     * @param bottleneckSize Size of bottleneck, 0 if train the whole model
     * @return lambda function of image pre-processor
     */
    private ImagePreprocessor getPreprocessor(Interpreter interpreter, int bottleneckSize) {
        if (bottleneckSize == 0) {
            return image -> image;
        } else {
            return image -> {
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("data", image);
                Map<String, Object> outputs = new HashMap<>();
                float[] bottleneck = new float[bottleneckSize];
                outputs.put("bottleneck", bottleneck);
                interpreter.runSignature(inputs, outputs, "load");
                return bottleneck;
            };
        }
    }

    /**
     * Data loader for Android. TFLite does not have its native data loader.
     *
     * @param labels List of labels, numbers in string.
     * @param imagesFolder Folder of dataset.
     * @param preprocessor Image pre-processor, either generate bottleneck, or identity.
     * @param height Height of image.
     * @param width Width of image.
     * @param numClasses Number of classes during training.
     * @return (image batches, label batches), list of float arrays.
     */
    private List<Pair<float[], float[]>> dataLoader(
            List<String> labels,
            String imagesFolder,
            ImagePreprocessor preprocessor,
            int height,
            int width,
            int numClasses
    ) {
        List<Pair<float[], float[]>> dataset = new ArrayList<>();
        for (String label: labels) {
            final String imageFileName = imagesFolder + label.split(" ")[0];
            final float[] imageBuffer = processImage(
                    BitmapFactory.decodeFile(imageFileName), preprocessor, height, width);
            final float[] imageLabel = encodeLabel(
                    Integer.parseInt(label.split(" ")[1]), numClasses);
            dataset.add(new Pair<>(imageBuffer, imageLabel));
        }
        return dataset;
    }

    /**
     * Convert list of list of data to list of batches where each batch is a 2D float array.
     *
     * @param batches Batches, inside each batch is a list of data, TFLite does not recognize list.
     * @param allocateSizePerData Size per each data.
     * @return A list of batches, each batch is a 2-dimension float array.
     */
    private Pair<List<float[][]>, List<float[][]>> generateBatchBuffers(
            List<Pair<float[], float[]>> batches,
            int batchSize,
            int allocateSizePerData,
            int allocateSizePerLabel) {
        List<float[][]> dataBatchBuffers = new ArrayList<>();
        List<float[][]> labelBatchBuffers = new ArrayList<>();
        for (int i = 0; i < batches.size(); i += batchSize) {
            float[][] newDataBatchBuffer = new float[min(batchSize, batches.size() - i)][allocateSizePerData];
            float[][] newLabelBatchBuffer = new float[min(batchSize, batches.size() - i)][allocateSizePerLabel];
            for (int j = i; j < min(i + 16, batches.size()); ++j) {
                newDataBatchBuffer[j - i] = batches.get(j).first;
                newLabelBatchBuffer[j - i] = batches.get(i).second;
            }
            dataBatchBuffers.add(newDataBatchBuffer);
            labelBatchBuffers.add(newLabelBatchBuffer);
        }
        return new Pair<>(dataBatchBuffers, labelBatchBuffers);
    }

    /**
     * Preprocess the image and convert it into a TensorImage for classification.
     *
     * @param image Image in Bitmap.
     * @param preprocessor Pre-process image to bottleneck, or remain the same.
     * @param targetHeight Target height after resize.
     * @param targetWidth Target width after resize.
     * @return Image in float array.
     */
    private float[] processImage(
            Bitmap image,
            ImagePreprocessor preprocessor,
            int targetHeight,
            int targetWidth) {
        int height = image.getHeight();
        int width = image.getWidth();
        int cropSize = min(height, width);
        ImageProcessor.Builder builder = new ImageProcessor.Builder();
        ImageProcessor imageProcessor = builder
                .add(new ResizeWithCropOrPadOp(cropSize, cropSize))
                .add(new ResizeOp(targetHeight, targetWidth, ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0f, 255f))
                .build();
        TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
        tensorImage.load(image);
        return preprocessor.operation(imageProcessor.process(tensorImage).getTensorBuffer().getFloatArray());
    }


    /**
     * encode the classes name to float array
     *
     * @param id ID of the current data.
     * @param numClasses Number of classes participating in training.
     * @return Label in float array.
     */
    private float[] encodeLabel(int id, int numClasses) {
        float[] classArray = new float[numClasses];
        Arrays.fill(classArray, 0f);
        classArray[id] = 1f;
        return classArray;
    }
}