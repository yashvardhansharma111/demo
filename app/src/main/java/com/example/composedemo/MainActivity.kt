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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    
    // DEBUG: Body landmarks in screen space for debug overlay
    var debugBodyLandmarks by remember { mutableStateOf<BodyLandmarks?>(null) }
    
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
                onMeshDeformed = { mesh -> deformedMesh = mesh },
                onLandmarksComputed = { landmarks -> debugBodyLandmarks = landmarks }
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
        
        // OpenGL mesh renderer overlay - RENDERING ON BLACK SILHOUETTE POINTS
        if (overlayMode == OverlayMode.SHIRT && 
            deformedMesh != null && 
            metadata != null &&
            deformedMesh!!.torsoVertices.isNotEmpty()) {
            GarmentMeshOverlay(
                deformedMesh = deformedMesh!!,
                textureResourceId = R.drawable.shirt_overlay
            )
        }
        
        // DEBUG: Visual debugging overlay - ONLY RED DOTS FOR MEDIAPIPE LANDMARKS
        if (overlayMode == OverlayMode.SHIRT && 
            debugBodyLandmarks != null &&
            screenWidth > 0 && 
            screenHeight > 0) {
            GarmentDebugOverlay(
                bodyLandmarks = debugBodyLandmarks!!,
                screenWidth = screenWidth,
                screenHeight = screenHeight
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
    onMeshDeformed: (DeformedMesh?) -> Unit,
    onLandmarksComputed: (BodyLandmarks) -> Unit
) {
    try {
        if (transformData != null && 
            metadata != null && 
            garmentMesh != null &&
            cameraImageWidth > 0 && 
            cameraImageHeight > 0 &&
            screenWidth > 0 &&
            screenHeight > 0) {
            
            // COORDINATE CONVERSION: MediaPipe (0-1) → Screen (pixels)
            // FIX: Flip horizontally, rotate 90 degrees, AND rotate 180 degrees (upside down)
            // Combined transformation: newX = (1 - oldY) * screenWidth, newY = oldX * screenHeight
            val bodyLandmarks = BodyLandmarks(
                leftShoulder = Offset(
                    (1.0f - transformData.leftArm.shoulder.y) * screenWidth,
                    transformData.leftArm.shoulder.x * screenHeight
                ),
                rightShoulder = Offset(
                    (1.0f - transformData.rightArm.shoulder.y) * screenWidth,
                    transformData.rightArm.shoulder.x * screenHeight
                ),
                leftElbow = Offset(
                    (1.0f - transformData.leftArm.elbow.y) * screenWidth,
                    transformData.leftArm.elbow.x * screenHeight
                ),
                rightElbow = Offset(
                    (1.0f - transformData.rightArm.elbow.y) * screenWidth,
                    transformData.rightArm.elbow.x * screenHeight
                ),
                leftWrist = Offset(
                    (1.0f - transformData.leftArm.wrist.y) * screenWidth,
                    transformData.leftArm.wrist.x * screenHeight
                ),
                rightWrist = Offset(
                    (1.0f - transformData.rightArm.wrist.y) * screenWidth,
                    transformData.rightArm.wrist.x * screenHeight
                ),
                leftHip = Offset(
                    (1.0f - transformData.leftHip.y) * screenWidth,
                    transformData.leftHip.x * screenHeight
                ),
                rightHip = Offset(
                    (1.0f - transformData.rightHip.y) * screenWidth,
                    transformData.rightHip.x * screenHeight
                )
            )

            // Log the converted coordinates to verify they're correct
            android.util.Log.d("MAIN_ACTIVITY_COORDS", """
                ===== Screen Space Coordinates (after scaling) =====
                Screen size: ${screenWidth}x${screenHeight}
                Left Shoulder: (${bodyLandmarks.leftShoulder.x}, ${bodyLandmarks.leftShoulder.y})
                Right Shoulder: (${bodyLandmarks.rightShoulder.x}, ${bodyLandmarks.rightShoulder.y})
                Left Hip: (${bodyLandmarks.leftHip.x}, ${bodyLandmarks.leftHip.y})
                Right Hip: (${bodyLandmarks.rightHip.x}, ${bodyLandmarks.rightHip.y})
                
                Expected: shoulders.y < hips.y (shoulders higher on screen than hips)
                Actual: shoulders.y (${(bodyLandmarks.leftShoulder.y + bodyLandmarks.rightShoulder.y) / 2f}) vs hips.y (${(bodyLandmarks.leftHip.y + bodyLandmarks.rightHip.y) / 2f})
            """.trimIndent())

            // Rest of validation stays the same...
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

            // DEBUG: Store landmarks for debug overlay
            onLandmarksComputed(bodyLandmarks)

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
