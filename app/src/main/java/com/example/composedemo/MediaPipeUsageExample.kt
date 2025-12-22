package com.example.composedemo

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

@Composable
fun CameraScreenWithMediaPipe() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var shirtTransformData by remember { mutableStateOf<ShirtTransformData?>(null) }
    
    val mediaPipeProcessor = remember {
        MediaPipeProcessor(context) { transformData ->
            shirtTransformData = transformData
        }
    }
    
    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val executor: Executor = ContextCompat.getMainExecutor(context)
        
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            mediaPipeProcessor.processImageProxy(imageProxy)
        }
        
        val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Use shirtTransformData in Compose
    // Example: Apply graphicsLayer transformations
    // Modifier.graphicsLayer {
    //     translationX = shirtTransformData?.torsoCenter?.x?.let { it * screenWidth } ?: 0f
    //     translationY = shirtTransformData?.torsoCenter?.y?.let { it * screenHeight } ?: 0f
    //     scaleX = shirtTransformData?.shoulderWidth ?: 1f
    // }
}

// Example: Convert normalized coordinates (0-1) to screen space
fun Point.toScreenCoordinates(screenWidth: Float, screenHeight: Float): androidx.compose.ui.geometry.Offset {
    return androidx.compose.ui.geometry.Offset(
        x = this.x * screenWidth,
        y = this.y * screenHeight
    )
}

// Example: Use in Compose graphicsLayer
@Composable
fun TransformedClothingOverlay(
    transformData: ShirtTransformData?,
    screenWidth: Float,
    screenHeight: Float
) {
    if (transformData == null) return
    
    val torsoScreenPos = transformData.torsoCenter.toScreenCoordinates(screenWidth, screenHeight)
    val leftShoulderPos = transformData.leftArm.shoulder.toScreenCoordinates(screenWidth, screenHeight)
    val rightShoulderPos = transformData.rightArm.shoulder.toScreenCoordinates(screenWidth, screenHeight)
    
    // Use these coordinates with graphicsLayer for real-time transformation
    // Modifier.graphicsLayer {
    //     translationX = torsoScreenPos.x - (width / 2)
    //     translationY = torsoScreenPos.y - (height / 2)
    //     scaleX = transformData.shoulderWidth * screenWidth / defaultWidth
    // }
}

