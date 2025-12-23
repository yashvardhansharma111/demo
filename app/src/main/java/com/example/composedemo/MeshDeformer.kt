package com.example.composedemo

import androidx.compose.ui.geometry.Offset

/**
 * Body landmarks in screen space (pixels).
 * Converted from MediaPipe normalized coordinates.
 */
data class BodyLandmarks(
    val leftShoulder: Offset,
    val rightShoulder: Offset,
    val leftElbow: Offset,
    val rightElbow: Offset,
    val leftWrist: Offset,
    val rightWrist: Offset,
    val leftHip: Offset,
    val rightHip: Offset
)

/**
 * Deformed vertex position in screen space after weighted skinning.
 */
data class DeformedVertex(
    val screenX: Float,
    val screenY: Float,
    val u: Float,  // UV coordinate for texture sampling
    val v: Float
)

/**
 * Deforms garment mesh vertices from garment space to screen space
 * using weighted skinning based on body landmarks.
 */
object MeshDeformer {
    /**
     * Deforms all mesh vertices for a frame.
     * Returns deformed vertices ready for OpenGL rendering.
     * 
     * CRITICAL: Converts MediaPipe normalized coordinates (0-1) to OpenGL NDC space (-1 to +1).
     */
    fun deformMesh(
        mesh: GarmentMesh,
        bodyLandmarks: BodyLandmarks,
        metadata: GarmentMetadata,
        screenWidth: Int,
        screenHeight: Int
    ): DeformedMesh {
        // Validate landmarks are not zero/invalid
        if (!isValidLandmarks(bodyLandmarks)) {
            // Return empty mesh - renderer will skip rendering
            return DeformedMesh(
                torsoVertices = emptyList(),
                leftSleeveVertices = emptyList(),
                rightSleeveVertices = emptyList()
            )
        }
        // Compute body reference size for scaling
        val shoulderWidth = distance(
            bodyLandmarks.leftShoulder.x, bodyLandmarks.leftShoulder.y,
            bodyLandmarks.rightShoulder.x, bodyLandmarks.rightShoulder.y
        )
        val torsoHeight = distance(
            (bodyLandmarks.leftShoulder.x + bodyLandmarks.rightShoulder.x) / 2f,
            (bodyLandmarks.leftShoulder.y + bodyLandmarks.rightShoulder.y) / 2f,
            (bodyLandmarks.leftHip.x + bodyLandmarks.rightHip.x) / 2f,
            (bodyLandmarks.leftHip.y + bodyLandmarks.rightHip.y) / 2f
        )

        // Apply fit factors with clamping (±15% safety limit)
        val baseWidth = shoulderWidth * metadata.fit.widthFactor
        val baseHeight = torsoHeight * metadata.fit.heightFactor
        val clampedWidth = baseWidth.coerceIn(shoulderWidth * 0.85f, shoulderWidth * 1.15f)
        val clampedHeight = baseHeight.coerceIn(torsoHeight * 0.85f, torsoHeight * 1.15f)

        // Compute scale factors (garment space to screen space)
        // Garment space is normalized (0-1), so we need to map to actual screen dimensions
        val scaleX = clampedWidth / metadata.garmentSpace.width
        val scaleY = clampedHeight / metadata.garmentSpace.height

        // Deform each region - pass screen dimensions for NDC conversion
        val torsoVertices = mesh.torsoVertices.map { vertex ->
            deformVertex(vertex, bodyLandmarks, scaleX, scaleY, isTorso = true, screenWidth, screenHeight)
        }

        val leftSleeveVertices = mesh.leftSleeveVertices.map { vertex ->
            deformVertex(vertex, bodyLandmarks, scaleX, scaleY, isTorso = false, screenWidth, screenHeight)
        }

        val rightSleeveVertices = mesh.rightSleeveVertices.map { vertex ->
            deformVertex(vertex, bodyLandmarks, scaleX, scaleY, isTorso = false, screenWidth, screenHeight)
        }

        return DeformedMesh(
            torsoVertices = torsoVertices,
            leftSleeveVertices = leftSleeveVertices,
            rightSleeveVertices = rightSleeveVertices
        )
    }

    /**
     * Deforms a single vertex using weighted skinning.
     * Formula: vertexPosition = Σ(weight_i * anchorPosition_i)
     * 
     * CRITICAL: Converts screen coordinates to OpenGL NDC space (-1 to +1).
     */
    private fun deformVertex(
        vertex: MeshVertex,
        landmarks: BodyLandmarks,
        scaleX: Float,
        scaleY: Float,
        isTorso: Boolean,
        screenWidth: Int,
        screenHeight: Int
    ): DeformedVertex {
        val weights = vertex.boneWeights

        // Normalize weights to ensure sum = 1.0 (prevent zero-sum causing (0,0) vertices)
        val normalizedWeights = normalizeWeights(weights, isTorso)

        // Weighted sum of anchor positions in screen space
        var screenX = 0f
        var screenY = 0f

        if (isTorso) {
            // Torso uses shoulders and hips for bilinear interpolation
            screenX += normalizedWeights.leftShoulder * landmarks.leftShoulder.x
            screenY += normalizedWeights.leftShoulder * landmarks.leftShoulder.y

            screenX += normalizedWeights.rightShoulder * landmarks.rightShoulder.x
            screenY += normalizedWeights.rightShoulder * landmarks.rightShoulder.y

            screenX += normalizedWeights.leftHip * landmarks.leftHip.x
            screenY += normalizedWeights.leftHip * landmarks.leftHip.y

            screenX += normalizedWeights.rightHip * landmarks.rightHip.x
            screenY += normalizedWeights.rightHip * landmarks.rightHip.y
        } else {
            // Sleeves use arm chain (shoulder → elbow → wrist)
            // Determine which arm based on which weights are non-zero
            if (normalizedWeights.leftShoulder > 0f || normalizedWeights.leftElbow > 0f || normalizedWeights.leftWrist > 0f) {
                // Left sleeve
                screenX += normalizedWeights.leftShoulder * landmarks.leftShoulder.x
                screenY += normalizedWeights.leftShoulder * landmarks.leftShoulder.y
                screenX += normalizedWeights.leftElbow * landmarks.leftElbow.x
                screenY += normalizedWeights.leftElbow * landmarks.leftElbow.y
                screenX += normalizedWeights.leftWrist * landmarks.leftWrist.x
                screenY += normalizedWeights.leftWrist * landmarks.leftWrist.y
            } else {
                // Right sleeve
                screenX += normalizedWeights.rightShoulder * landmarks.rightShoulder.x
                screenY += normalizedWeights.rightShoulder * landmarks.rightShoulder.y
                screenX += normalizedWeights.rightElbow * landmarks.rightElbow.x
                screenY += normalizedWeights.rightElbow * landmarks.rightElbow.y
                screenX += normalizedWeights.rightWrist * landmarks.rightWrist.x
                screenY += normalizedWeights.rightWrist * landmarks.rightWrist.y
            }
        }

        // CRITICAL: Convert screen coordinates to OpenGL NDC space (-1 to +1)
        // OpenGL NDC: (-1,-1) is bottom-left, (1,1) is top-right
        // Screen: (0,0) is top-left, (width,height) is bottom-right
        // Formula: ndcX = (screenX / screenWidth) * 2 - 1
        //          ndcY = 1 - (screenY / screenHeight) * 2  (flip Y because screen Y=0 is top, OpenGL Y=-1 is bottom)
        val ndcX = (screenX / screenWidth.toFloat()) * 2f - 1f
        val ndcY = 1f - (screenY / screenHeight.toFloat()) * 2f

        return DeformedVertex(
            screenX = ndcX,
            screenY = ndcY,
            u = vertex.u,
            v = vertex.v
        )
    }
    
    /**
     * Normalizes bone weights to ensure sum = 1.0, preventing zero-sum causing (0,0) vertices.
     */
    private fun normalizeWeights(weights: BoneWeights, isTorso: Boolean): BoneWeights {
        val sum = if (isTorso) {
            weights.leftShoulder + weights.rightShoulder + weights.leftHip + weights.rightHip
        } else {
            weights.leftShoulder + weights.rightShoulder + 
            weights.leftElbow + weights.rightElbow + 
            weights.leftWrist + weights.rightWrist
        }
        
        // If sum is too small, use default weights (prevents division by zero and invalid vertices)
        if (sum < 0.0001f) {
            return if (isTorso) {
                BoneWeights(0.25f, 0.25f, 0f, 0f, 0f, 0f, 0.25f, 0.25f)
            } else {
                BoneWeights(0.33f, 0.33f, 0.17f, 0.17f, 0f, 0f, 0f, 0f)
            }
        }
        
        return if (isTorso) {
            BoneWeights(
                leftShoulder = weights.leftShoulder / sum,
                rightShoulder = weights.rightShoulder / sum,
                leftElbow = 0f,
                rightElbow = 0f,
                leftWrist = 0f,
                rightWrist = 0f,
                leftHip = weights.leftHip / sum,
                rightHip = weights.rightHip / sum
            )
        } else {
            BoneWeights(
                leftShoulder = weights.leftShoulder / sum,
                rightShoulder = weights.rightShoulder / sum,
                leftElbow = weights.leftElbow / sum,
                rightElbow = weights.rightElbow / sum,
                leftWrist = weights.leftWrist / sum,
                rightWrist = weights.rightWrist / sum,
                leftHip = 0f,
                rightHip = 0f
            )
        }
    }
    
    /**
     * Validates that landmarks are not zero/invalid.
     */
    private fun isValidLandmarks(landmarks: BodyLandmarks): Boolean {
        // Check for NaN or Infinity
        fun isValidFloat(f: Float): Boolean = f.isFinite() && f < Float.MAX_VALUE && f > -Float.MAX_VALUE
        
        // Relaxed validation: only check that values are finite and reasonable
        // Don't require > 0 because coordinates might be negative after rotation
        val shouldersValid = isValidFloat(landmarks.leftShoulder.x) && 
                             isValidFloat(landmarks.leftShoulder.y) &&
                             isValidFloat(landmarks.rightShoulder.x) && 
                             isValidFloat(landmarks.rightShoulder.y)
        
        val hipsValid = isValidFloat(landmarks.leftHip.x) && 
                       isValidFloat(landmarks.leftHip.y) &&
                       isValidFloat(landmarks.rightHip.x) && 
                       isValidFloat(landmarks.rightHip.y)
        
        // Check that shoulders are not at the same point (indicates invalid detection)
        val shoulderDistance = distance(
            landmarks.leftShoulder.x, landmarks.leftShoulder.y,
            landmarks.rightShoulder.x, landmarks.rightShoulder.y
        )
        
        return shouldersValid && hipsValid && shoulderDistance > 10f  // At least 10 pixels apart
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/**
 * Complete deformed mesh ready for rendering.
 */
data class DeformedMesh(
    val torsoVertices: List<DeformedVertex>,
    val leftSleeveVertices: List<DeformedVertex>,
    val rightSleeveVertices: List<DeformedVertex>
)

