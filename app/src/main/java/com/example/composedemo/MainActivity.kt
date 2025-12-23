package com.example.composedemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.composedemo.ui.theme.ComposeDemoTheme

enum class OverlayMode {
    SHIRT,
    PANTS
}

class MainActivity : ComponentActivity() {
    private val hasCameraPermission = mutableStateOf(false)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission.value = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check initial permission state
        hasCameraPermission.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        setContent {
            ComposeDemoTheme {
                val permissionGranted = hasCameraPermission.value
                
                CameraScreen(
                    hasPermission = permissionGranted,
                    onRequestPermission = {
                        if (!permissionGranted) {
                            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun CameraScreen(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayMode by remember { mutableStateOf(OverlayMode.SHIRT) }
    
    // Load garment metadata once - handle errors outside composable
    val metadata = remember {
        loadMetadataSafely(context)
    }
    
    // Generate mesh once from metadata
    val garmentMesh = remember(metadata) {
        metadata?.let { generateMeshSafely(it) }
    }
    
    // Current deformed mesh state
    var deformedMesh by remember { mutableStateOf<DeformedMesh?>(null) }
    
    // Screen dimensions for coordinate conversion
    var screenWidth by remember { mutableStateOf(0) }
    var screenHeight by remember { mutableStateOf(0) }
    var cameraImageWidth by remember { mutableStateOf(0) }
    var cameraImageHeight by remember { mutableStateOf(0) }
    
    // MediaPipe processor - initialize safely
    val mediaPipeProcessor = remember {
        initializeMediaPipeSafely(context) { transformData ->
            // CRITICAL: Only process if we have valid pose data
            if (transformData == null) {
                deformedMesh = null
                return@initializeMediaPipeSafely
            }
            
            // Validate essential landmarks exist
            val hasValidShoulders = transformData.leftArm.shoulder.x > 0f && 
                                   transformData.rightArm.shoulder.x > 0f &&
                                   transformData.leftArm.shoulder.y > 0f && 
                                   transformData.rightArm.shoulder.y > 0f
            
            if (!hasValidShoulders) {
                deformedMesh = null
                return@initializeMediaPipeSafely
            }
            
            processLandmarksSafely(
                transformData = transformData,
                metadata = metadata,
                garmentMesh = garmentMesh,
                cameraImageWidth = cameraImageWidth,
                cameraImageHeight = cameraImageHeight,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                onMeshDeformed = { mesh -> deformedMesh = mesh }
            )
        }
    }
    
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            onRequestPermission()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            mediaPipeProcessor?.release()
        }
    }
    
    if (!hasPermission) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Camera permission required")
        }
        return
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview layer
        if (mediaPipeProcessor != null) {
            CameraPreviewWithAnalysis(
                modifier = Modifier.fillMaxSize(),
                mediaPipeProcessor = mediaPipeProcessor,
                onImageSizeChanged = { width, height ->
                    cameraImageWidth = width
                    cameraImageHeight = height
                },
                onScreenSizeChanged = { width, height ->
                    screenWidth = width
                    screenHeight = height
                }
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to initialize MediaPipe")
            }
        }
        
        // OpenGL mesh renderer overlay - ONLY render when we have valid mesh
        if (overlayMode == OverlayMode.SHIRT && 
            deformedMesh != null && 
            metadata != null &&
            deformedMesh!!.torsoVertices.isNotEmpty()) {
            GarmentMeshOverlay(
                deformedMesh = deformedMesh!!,
                textureResourceId = R.drawable.shirt_overlay
            )
        }
        
        // Toggle button
        ModeToggleButton(
            currentMode = overlayMode,
            onModeChange = { overlayMode = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@Composable
fun CameraPreviewWithAnalysis(
    modifier: Modifier = Modifier,
    mediaPipeProcessor: MediaPipeProcessor,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
    onImageSizeChanged: (Int, Int) -> Unit,
    onScreenSizeChanged: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            
            // Store screen dimensions
            previewView.post {
                onScreenSizeChanged(previewView.width, previewView.height)
            }
            
            cameraProviderFuture.addListener(
                Runnable {
                    try {
                        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                        
                        val preview: Preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        
                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            // Store camera image dimensions
                            onImageSizeChanged(imageProxy.width, imageProxy.height)
                            mediaPipeProcessor.processImageProxy(imageProxy)
                        }
                        
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                executor
            )
            
            previewView
        },
        modifier = modifier
    )
}

@Composable
fun GarmentMeshOverlay(
    deformedMesh: DeformedMesh,
    textureResourceId: Int
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { ctx ->
            val glView = GarmentGLView(ctx)
            glView.setTexture(textureResourceId)
            glView
        },
        update = { glView ->
            glView.updateMesh(deformedMesh)
        },
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent) // Ensure transparent background
    )
}

@Composable
fun ModeToggleButton(
    currentMode: OverlayMode,
    onModeChange: (OverlayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = {
            onModeChange(
                if (currentMode == OverlayMode.SHIRT) OverlayMode.PANTS else OverlayMode.SHIRT
            )
        },
        modifier = modifier
    ) {
        Text(
            text = if (currentMode == OverlayMode.SHIRT) "Switch to Pants" else "Switch to Shirt"
        )
    }
}

// Helper functions to handle errors outside composable scope
private fun loadMetadataSafely(context: android.content.Context): GarmentMetadata? {
    return try {
        GarmentMetadataLoader.loadFromAssets(context, "shirt_overlay.json")
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Failed to load metadata", e)
        null
    }
}

private fun generateMeshSafely(metadata: GarmentMetadata): GarmentMesh? {
    return try {
        GarmentMeshGenerator.generateMesh(metadata)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Failed to generate mesh", e)
        null
    }
}

private fun initializeMediaPipeSafely(
    context: android.content.Context,
    onLandmarksDetected: (ShirtTransformData?) -> Unit
): MediaPipeProcessor? {
    return try {
        MediaPipeProcessor(context, onLandmarksDetected)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Failed to initialize MediaPipe", e)
        null
    }
}

private fun processLandmarksSafely(
    transformData: ShirtTransformData?,
    metadata: GarmentMetadata?,
    garmentMesh: GarmentMesh?,
    cameraImageWidth: Int,
    cameraImageHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    onMeshDeformed: (DeformedMesh?) -> Unit
) {
    try {
        if (transformData != null && 
            metadata != null && 
            garmentMesh != null &&
            cameraImageWidth > 0 && 
            cameraImageHeight > 0 &&
            screenWidth > 0 &&
            screenHeight > 0) {
            
            // Convert MediaPipe normalized coordinates (0-1) to screen space
            // CRITICAL: MediaPipe coordinates are relative to camera image orientation
            // Camera image might be rotated (portrait camera in landscape screen or vice versa)
            // For front camera in portrait mode: MediaPipe X = screen Y, MediaPipe Y = 1 - screen X
            // This accounts for the 90-degree rotation between camera image and screen
            
            // Check if camera is in portrait (height > width) vs landscape
            val isCameraPortrait = cameraImageHeight > cameraImageWidth
            val isScreenPortrait = screenHeight > screenWidth
            
            val bodyLandmarks = if (isCameraPortrait != isScreenPortrait) {
                // Camera and screen have different orientations - need to rotate coordinates
                // Swap X/Y and flip one axis
                BodyLandmarks(
                    leftShoulder = Offset(
                        transformData.leftArm.shoulder.y * screenWidth,
                        (1f - transformData.leftArm.shoulder.x) * screenHeight
                    ),
                    rightShoulder = Offset(
                        transformData.rightArm.shoulder.y * screenWidth,
                        (1f - transformData.rightArm.shoulder.x) * screenHeight
                    ),
                    leftElbow = Offset(
                        transformData.leftArm.elbow.y * screenWidth,
                        (1f - transformData.leftArm.elbow.x) * screenHeight
                    ),
                    rightElbow = Offset(
                        transformData.rightArm.elbow.y * screenWidth,
                        (1f - transformData.rightArm.elbow.x) * screenHeight
                    ),
                    leftWrist = Offset(
                        transformData.leftArm.wrist.y * screenWidth,
                        (1f - transformData.leftArm.wrist.x) * screenHeight
                    ),
                    rightWrist = Offset(
                        transformData.rightArm.wrist.y * screenWidth,
                        (1f - transformData.rightArm.wrist.x) * screenHeight
                    ),
                    leftHip = Offset(
                        transformData.leftHip.y * screenWidth,
                        (1f - transformData.leftHip.x) * screenHeight
                    ),
                    rightHip = Offset(
                        transformData.rightHip.y * screenWidth,
                        (1f - transformData.rightHip.x) * screenHeight
                    )
                )
            } else {
                // Same orientation - direct mapping
                BodyLandmarks(
                    leftShoulder = Offset(
                        transformData.leftArm.shoulder.x * screenWidth,
                        transformData.leftArm.shoulder.y * screenHeight
                    ),
                    rightShoulder = Offset(
                        transformData.rightArm.shoulder.x * screenWidth,
                        transformData.rightArm.shoulder.y * screenHeight
                    ),
                    leftElbow = Offset(
                        transformData.leftArm.elbow.x * screenWidth,
                        transformData.leftArm.elbow.y * screenHeight
                    ),
                    rightElbow = Offset(
                        transformData.rightArm.elbow.x * screenWidth,
                        transformData.rightArm.elbow.y * screenHeight
                    ),
                    leftWrist = Offset(
                        transformData.leftArm.wrist.x * screenWidth,
                        transformData.leftArm.wrist.y * screenHeight
                    ),
                    rightWrist = Offset(
                        transformData.rightArm.wrist.x * screenWidth,
                        transformData.rightArm.wrist.y * screenHeight
                    ),
                    leftHip = Offset(
                        transformData.leftHip.x * screenWidth,
                        transformData.leftHip.y * screenHeight
                    ),
                    rightHip = Offset(
                        transformData.rightHip.x * screenWidth,
                        transformData.rightHip.y * screenHeight
                    )
                )
            }
            
            // Relaxed validation: only check that coordinates are reasonable (not NaN/Infinity)
            // Allow some out-of-bounds since camera aspect ratio might differ
            val allLandmarksValid = listOf(
                bodyLandmarks.leftShoulder, bodyLandmarks.rightShoulder,
                bodyLandmarks.leftHip, bodyLandmarks.rightHip
            ).all { landmark ->
                landmark.x.isFinite() && landmark.y.isFinite() &&
                landmark.x > -screenWidth && landmark.x < screenWidth * 2f &&
                landmark.y > -screenHeight && landmark.y < screenHeight * 2f
            }
            
            if (!allLandmarksValid) {
                android.util.Log.w("MainActivity", "Landmarks validation failed")
                onMeshDeformed(null)
                return
            }
            
            // Deform mesh based on body landmarks
            val deformed = MeshDeformer.deformMesh(
                mesh = garmentMesh,
                bodyLandmarks = bodyLandmarks,
                metadata = metadata,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
            onMeshDeformed(deformed)
        } else {
            onMeshDeformed(null)
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error processing landmarks", e)
        onMeshDeformed(null)
    }
}
