package com.example.composedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class Point(
    val x: Float,
    val y: Float
)

data class ArmLandmarkData(
    val shoulder: Point,
    val elbow: Point,
    val wrist: Point
)

data class ShirtTransformData(
    val torsoCenter: Point,
    val shoulderWidth: Float,
    val leftArm: ArmLandmarkData,
    val rightArm: ArmLandmarkData,
    val leftHip: Point,
    val rightHip: Point
)

class MediaPipeProcessor(
    context: Context,
    private val onLandmarksDetected: (ShirtTransformData?) -> Unit
) {
    private var poseLandmarker: PoseLandmarker? = null
    private var handLandmarker: HandLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var latestPoseResult: PoseLandmarkerResult? = null
    
    init {
        initializeModels(context)
    }
    
    private fun initializeModels(context: Context) {
        val poseModelPath = copyAssetToCache(context, "pose_landmarker_lite.task")
        val handModelPath = copyAssetToCache(context, "hand_landmarker.task")
        
        val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(poseModelPath)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                processPoseResult(result)
            }
            .build()
        
        val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(handModelPath)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setResultListener { _, _ -> }
            .build()
        
        poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
        handLandmarker = HandLandmarker.createFromOptions(context, handOptions)
    }
    
    private fun copyAssetToCache(context: Context, filename: String): String {
        val cacheDir = File(context.cacheDir, "mediapipe")
        cacheDir.mkdirs()
        val file = File(cacheDir, filename)
        
        if (!file.exists()) {
            context.assets.open(filename).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        return file.absolutePath
    }
    
    private fun processPoseResult(result: PoseLandmarkerResult) {
        latestPoseResult = result
        extractShirtTransformData()
    }
    
    private fun processHandResult(result: HandLandmarkerResult) {
        // Hand landmarks can be used for additional transformations if needed
    }
    
    private fun extractShirtTransformData() {
        val poseResult = latestPoseResult ?: run {
            mainHandler.post {
                onLandmarksDetected(null)
            }
            return
        }
        
        if (poseResult.landmarks().isEmpty()) {
            mainHandler.post {
                onLandmarksDetected(null)
            }
            return
        }
        
        val landmarks = poseResult.landmarks()[0]
        if (landmarks.size < 33) {
            onLandmarksDetected(null)
            return
        }
        
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]
        val leftElbow = landmarks[13]
        val rightElbow = landmarks[14]
        val leftWrist = landmarks[15]
        val rightWrist = landmarks[16]
        val leftHip = landmarks[23]
        val rightHip = landmarks[24]
        
        val torsoCenterX = (leftHip.x() + rightHip.x()) / 2f
        val torsoCenterY = (leftHip.y() + rightHip.y()) / 2f
        val torsoCenter = Point(torsoCenterX, torsoCenterY)
        
        val shoulderWidth = kotlin.math.abs(rightShoulder.x() - leftShoulder.x())
        
        val leftArm = ArmLandmarkData(
            shoulder = Point(leftShoulder.x(), leftShoulder.y()),
            elbow = Point(leftElbow.x(), leftElbow.y()),
            wrist = Point(leftWrist.x(), leftWrist.y())
        )
        
        val rightArm = ArmLandmarkData(
            shoulder = Point(rightShoulder.x(), rightShoulder.y()),
            elbow = Point(rightElbow.x(), rightElbow.y()),
            wrist = Point(rightWrist.x(), rightWrist.y())
        )
        
        val transformData = ShirtTransformData(
            torsoCenter = torsoCenter,
            shoulderWidth = shoulderWidth,
            leftArm = leftArm,
            rightArm = rightArm,
            leftHip = Point(leftHip.x(), leftHip.y()),
            rightHip = Point(rightHip.x(), rightHip.y())
        )
        
        // Ensure callback runs on main thread for Compose state updates
        mainHandler.post {
            onLandmarksDetected(transformData)
        }
    }
    
    fun processImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        
        val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
        
        poseLandmarker?.detectAsync(mpImage, timestampMs)
        handLandmarker?.detectAsync(mpImage, timestampMs)
        
        imageProxy.close()
    }
    
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            100,
            out
        )
        
        val jpegArray = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegArray, 0, jpegArray.size)
    }
    
    fun release() {
        poseLandmarker?.close()
        handLandmarker?.close()
        poseLandmarker = null
        handLandmarker = null
    }
}

