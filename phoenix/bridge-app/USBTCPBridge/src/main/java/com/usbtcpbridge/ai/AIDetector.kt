package com.usbtcpbridge.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage
import java.util.concurrent.Executors

/**
 * AIDetector uses CameraX to capture frames and TFLite to run SSD MobileNet V1
 * for on-device object detection at a configured interval.
 */
class AIDetector(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onDetection: (label: String, score: Float, x: Int, y: Int, w: Int, h: Int) -> Unit
) {
    companion object {
        private const val TAG = "AIDetector"
        private const val MODEL_FILE = "mobilenet.tflite"
        // Run inference every 2 seconds to save battery and heat
        private const val INFERENCE_INTERVAL_MS = 2000L
    }

    private var objectDetector: ObjectDetector? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastInferenceTime = 0L

    fun start() {
        Log.i(TAG, "Starting AI Detector")
        setupModel()
        startCamera()
    }

    fun stop() {
        Log.i(TAG, "Stopping AI Detector")
        objectDetector?.close()
        objectDetector = null
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupModel() {
        try {
            val baseOptions = BaseOptions.builder().setNumThreads(2).build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(3)
                .setScoreThreshold(0.5f) // 50% confidence
                .build()
            
            objectDetector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            Log.i(TAG, "TFLite Model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                return@addListener
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                // Use default back camera
                val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalyzer
                )
                Log.i(TAG, "Camera bound to lifecycle for AI detection")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastInferenceTime < INFERENCE_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastInferenceTime = now

        val bitmap = imageProxy.toBitmap()
        imageProxy.close()

        val tensorImage = TensorImage.fromBitmap(bitmap)
        val detector = objectDetector ?: return
        
        try {
            val results = detector.detect(tensorImage)
            results?.forEach { detection ->
                val category = detection.categories.firstOrNull() ?: return@forEach
                val label = category.label
                val score = category.score
                val rect = detection.boundingBox
                
                onDetection(
                    label, 
                    score, 
                    rect.left.toInt(), 
                    rect.top.toInt(), 
                    rect.width().toInt(), 
                    rect.height().toInt()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference", e)
        }
    }
}
