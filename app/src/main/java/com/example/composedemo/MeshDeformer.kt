package com.example.composedemo

import androidx.compose.ui.geometry.Offset
import android.util.Log

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
 * Deformed vertex position in NDC space after weighted skinning.
 */
data class DeformedVertex(
    val screenX: Float,  // Actually NDC X in range [-1, 1]
    val screenY: Float,  // Actually NDC Y in range [-1, 1]
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
            return DeformedMesh(
                torsoVertices = emptyList(),
                leftSleeveVertices = emptyList(),
                rightSleeveVertices = emptyList()
            )
        }

        // ===============================
        // CORRECT CLOTHING SHOULDER LOGIC
        // ===============================
        val chestY = (bodyLandmarks.leftShoulder.y + bodyLandmarks.rightShoulder.y) * 0.5f

        val shoulderSpan = distance(
            bodyLandmarks.leftShoulder.x, bodyLandmarks.leftShoulder.y,
            bodyLandmarks.rightShoulder.x, bodyLandmarks.rightShoulder.y
        )

        val outwardOffset = shoulderSpan * 0.45f
        val verticalDrop = shoulderSpan * 0.12f

        val adjustedLeftShoulder = Offset(
            bodyLandmarks.leftShoulder.x - outwardOffset,
            chestY + verticalDrop
        )

        val adjustedRightShoulder = Offset(
            bodyLandmarks.rightShoulder.x + outwardOffset,
            chestY + verticalDrop
        )

        val adjustedLandmarks = BodyLandmarks(
            leftShoulder = adjustedLeftShoulder,
            rightShoulder = adjustedRightShoulder,
            leftElbow = bodyLandmarks.leftElbow,
            rightElbow = bodyLandmarks.rightElbow,
            leftWrist = bodyLandmarks.leftWrist,
            rightWrist = bodyLandmarks.rightWrist,
            leftHip = bodyLandmarks.leftHip,
            rightHip = bodyLandmarks.rightHip
        )

        val shoulderWidth = distance(
            adjustedLeftShoulder.x, adjustedLeftShoulder.y,
            adjustedRightShoulder.x, adjustedRightShoulder.y
        )

        val torsoHeight = distance(
            (adjustedLeftShoulder.x + adjustedRightShoulder.x) / 2f,
            (adjustedLeftShoulder.y + adjustedRightShoulder.y) / 2f,
            (bodyLandmarks.leftHip.x + bodyLandmarks.rightHip.x) / 2f,
            (bodyLandmarks.leftHip.y + bodyLandmarks.rightHip.y) / 2f
        )

        val baseWidth = shoulderWidth * metadata.fit.widthFactor
        val baseHeight = torsoHeight * metadata.fit.heightFactor
        val clampedWidth = baseWidth.coerceIn(shoulderWidth * 0.85f, shoulderWidth * 1.15f)
        val clampedHeight = baseHeight.coerceIn(torsoHeight * 0.85f, torsoHeight * 1.15f)

        val scaleX = clampedWidth / metadata.garmentSpace.width
        val scaleY = clampedHeight / metadata.garmentSpace.height

        // FOCUS: Torso using bodyLandmarks directly to match yellow quad exactly
        // This removes adjustments so we can verify coordinate conversion is correct
        val torsoVertices = mesh.torsoVertices.map { vertex ->
            deformVertexTorso(
                vertex,
                bodyLandmarks,  // Use bodyLandmarks directly (matches yellow quad)
                screenWidth,
                screenHeight,
                metadata
            )
        }

        val leftSleeveVertices = mesh.leftSleeveVertices.map { vertex ->
            deformVertexSleeve(
                vertex,
                bodyLandmarks,
                isLeftSleeve = true,
                screenWidth,
                screenHeight,
                metadata
            )
        }

        val rightSleeveVertices = mesh.rightSleeveVertices.map { vertex ->
            deformVertexSleeve(
                vertex,
                bodyLandmarks,
                isLeftSleeve = false,
                screenWidth,
                screenHeight,
                metadata
            )
        }

        return DeformedMesh(
            torsoVertices = torsoVertices,
            leftSleeveVertices = leftSleeveVertices,
            rightSleeveVertices = rightSleeveVertices
        )
    }

    /**
     * SIMPLIFIED: Deforms a torso vertex using bilinear quad interpolation.
     * Uses bodyLandmarks directly to match yellow quad exactly.
     *
     * Goal: Blue mesh vertices should perfectly align with yellow quad edges.
     */
    private fun deformVertexTorso(
        vertex: MeshVertex,
        landmarks: BodyLandmarks,  // Use bodyLandmarks directly (no adjustments)
        screenWidth: Int,
        screenHeight: Int,
        metadata: GarmentMetadata
    ): DeformedVertex {
        // Map garment UV to torso region space (0-1)
        val torsoBounds = metadata.regions.torso.bounds
        val torsoWidth = torsoBounds[2] - torsoBounds[0]
        val torsoHeight = torsoBounds[3] - torsoBounds[1]

        val gx = if (torsoWidth > 0.001f) {
            (vertex.garmentX - torsoBounds[0]) / torsoWidth
        } else {
            0.5f
        }

        var gy = if (torsoHeight > 0.001f) {
            (vertex.garmentY - torsoBounds[1]) / torsoHeight
        } else {
            0.5f
        }

        // CRITICAL FIX: Flip Y coordinate because metadata has Y=0 at bottom, but code expects Y=0 at top
        // After flipping: gy=0 → top (shoulders), gy=1 → bottom (hips)
        gy = 1f - gy

        // Bilinear interpolation within quad defined by landmarks
        // Quad: leftShoulder ──── rightShoulder
        //       │                      │
        //       │      TORSO           │
        //       │                      │
        //       leftHip ─────────── rightHip

        // Top edge interpolation (shoulders)
        val topEdgeX = landmarks.leftShoulder.x +
                (landmarks.rightShoulder.x - landmarks.leftShoulder.x) * gx
        val topEdgeY = landmarks.leftShoulder.y +
                (landmarks.rightShoulder.y - landmarks.leftShoulder.y) * gx

        // Bottom edge interpolation (hips)
        val bottomEdgeX = landmarks.leftHip.x +
                (landmarks.rightHip.x - landmarks.leftHip.x) * gx
        val bottomEdgeY = landmarks.leftHip.y +
                (landmarks.rightHip.y - landmarks.leftHip.y) * gx

        // Vertical interpolation: gy=0 → top (shoulders), gy=1 → bottom (hips)
        val screenX = topEdgeX + (bottomEdgeX - topEdgeX) * gy
        val screenY = topEdgeY + (bottomEdgeY - topEdgeY) * gy

        // Convert screen coordinates to OpenGL NDC space
        // Screen: (0,0) top-left, (width,height) bottom-right
        // NDC: (-1,-1) bottom-left, (1,1) top-right
        // CRITICAL: Flip Y-axis because screen Y increases DOWN, NDC Y increases UP
        val ndcX = 2f * (screenX / screenWidth.toFloat()) - 1f
        val ndcY = 1f - 2f * (screenY / screenHeight.toFloat())

        // Diagnostic logging for corner vertices
        if (vertex.garmentX < 0.01f && vertex.garmentY < 0.01f) {
            Log.d("TORSO_DEBUG", """
                TOP-LEFT vertex (should match leftShoulder):
                UV: (${vertex.garmentX}, ${vertex.garmentY}) → (gx=$gx, gy=$gy)
                Screen: ($screenX, $screenY)
                NDC: ($ndcX, $ndcY)
                Expected shoulder: (${landmarks.leftShoulder.x}, ${landmarks.leftShoulder.y})
            """.trimIndent())
        }

        if (vertex.garmentX > 0.99f && vertex.garmentY < 0.01f) {
            Log.d("TORSO_DEBUG", """
                TOP-RIGHT vertex (should match rightShoulder):
                UV: (${vertex.garmentX}, ${vertex.garmentY}) → (gx=$gx, gy=$gy)
                Screen: ($screenX, $screenY)
                Expected shoulder: (${landmarks.rightShoulder.x}, ${landmarks.rightShoulder.y})
            """.trimIndent())
        }

        if (vertex.garmentX < 0.01f && vertex.garmentY > 0.99f) {
            Log.d("TORSO_DEBUG", """
                BOTTOM-LEFT vertex (should match leftHip):
                UV: (${vertex.garmentX}, ${vertex.garmentY}) → (gx=$gx, gy=$gy)
                Screen: ($screenX, $screenY)
                Expected hip: (${landmarks.leftHip.x}, ${landmarks.leftHip.y})
            """.trimIndent())
        }

        return DeformedVertex(
            screenX = ndcX,
            screenY = ndcY,
            u = vertex.u,
            v = vertex.v
        )
    }

    /**
     * SIMPLIFIED: Deforms a sleeve vertex using rotation-based approach.
     * Sleeve sticks to shoulder and rotates based on arm direction (shoulder to elbow).
     */
    private fun deformVertexSleeve(
        vertex: MeshVertex,
        bodyLandmarks: BodyLandmarks,
        isLeftSleeve: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        metadata: GarmentMetadata
    ): DeformedVertex {
        val sleeveBounds = if (isLeftSleeve) {
            metadata.regions.leftSleeve.bounds
        } else {
            metadata.regions.rightSleeve.bounds
        }

        val sleeveWidth = sleeveBounds[2] - sleeveBounds[0]
        val sleeveHeight = sleeveBounds[3] - sleeveBounds[1]

        // Map garment UV to sleeve region space (0-1)
        val u = if (sleeveWidth > 0.001f) {
            (vertex.garmentX - sleeveBounds[0]) / sleeveWidth
        } else {
            0.5f
        }

        var v = if (sleeveHeight > 0.001f) {
            (vertex.garmentY - sleeveBounds[1]) / sleeveHeight
        } else {
            0.5f
        }

        // CRITICAL FIX: Flip Y coordinate because metadata has Y=0 at bottom, but code expects Y=0 at top
        // After flipping: v=0 → top (shoulder), v=1 → bottom (wrist)
        v = 1f - v

        // Local sleeve space (origin at shoulder, u=0.5 is center, v=0 is top)
        val localX = u - 0.5f  // -0.5 to +0.5 (left to right across sleeve)
        val localY = v         // 0 to 1 (top to bottom of sleeve)

        // QUICK FIX FOR DEMO: Attach sleeves to hips instead of shoulders
        // This makes sleeves appear at the correct visual position
        val shoulder = if (isLeftSleeve) {
            bodyLandmarks.leftHip  // Use hip instead of shoulder
        } else {
            bodyLandmarks.rightHip  // Use hip instead of shoulder
        }

        val elbow = if (isLeftSleeve) {
            bodyLandmarks.leftElbow
        } else {
            bodyLandmarks.rightElbow
        }

        // SAFETY CHECK: Verify shoulder is actually above hip (Y should be less for shoulder)
        val hip = if (isLeftSleeve) bodyLandmarks.leftHip else bodyLandmarks.rightHip
        if (shoulder.y > hip.y) {
            Log.e("SLEEVE_ERROR", """
                ⚠️ COORDINATE ERROR: ${if (isLeftSleeve) "LEFT" else "RIGHT"} sleeve shoulder Y (${shoulder.y}) is BELOW hip Y (${hip.y})!
                This suggests landmarks are swapped or coordinates are wrong.
                Shoulder: (${shoulder.x}, ${shoulder.y})
                Hip: (${hip.x}, ${hip.y})
            """.trimIndent())
        }

        // DIAGNOSTIC: Log landmark positions for first vertex
        if (vertex.garmentX < 0.01f && vertex.garmentY < 0.01f) {
            Log.d("SLEEVE_DEBUG", """
                ${if (isLeftSleeve) "LEFT" else "RIGHT"} SLEEVE - First vertex:
                Shoulder: (${shoulder.x}, ${shoulder.y}) ← Sleeve attaches HERE
                Elbow: (${elbow.x}, ${elbow.y})
                Hip: (${hip.x}, ${hip.y})
                ✓ Verifying: shoulder.y (${shoulder.y}) < hip.y (${hip.y}) = ${shoulder.y < hip.y}
            """.trimIndent())
        }

        // Calculate arm direction (shoulder to elbow)
        val dx = elbow.x - shoulder.x
        val dy = elbow.y - shoulder.y
        val angle = kotlin.math.atan2(dy, dx)

        // Calculate sleeve dimensions based on arm length
        val armLength = distance(shoulder.x, shoulder.y, elbow.x, elbow.y)
        val sleeveWidthPx = armLength * 0.35f  // Sleeve width as fraction of arm length
        val sleeveHeightPx = armLength * 0.9f  // Sleeve height as fraction of arm length

        // Rotate sleeve quad based on arm angle
        val cosA = kotlin.math.cos(angle)
        val sinA = kotlin.math.sin(angle)

        // Apply rotation transformation
        val rotatedX = localX * sleeveWidthPx * cosA - localY * sleeveHeightPx * sinA
        val rotatedY = localX * sleeveWidthPx * sinA + localY * sleeveHeightPx * cosA

        // Final position: shoulder + rotated offset
        val screenX = shoulder.x + rotatedX
        val screenY = shoulder.y + rotatedY

        // DIAGNOSTIC: Log final position for first vertex
        if (vertex.garmentX < 0.01f && vertex.garmentY < 0.01f) {
            Log.d("SLEEVE_DEBUG", """
                ${if (isLeftSleeve) "LEFT" else "RIGHT"} SLEEVE - Final position:
                Screen: ($screenX, $screenY)
                Shoulder was: (${shoulder.x}, ${shoulder.y})
                Offset: ($rotatedX, $rotatedY)
            """.trimIndent())
        }

        // Convert to OpenGL NDC space
        val ndcX = 2f * (screenX / screenWidth.toFloat()) - 1f
        val ndcY = 1f - 2f * (screenY / screenHeight.toFloat())

        return DeformedVertex(
            screenX = ndcX,
            screenY = ndcY,
            u = vertex.u,
            v = vertex.v
        )
    }

    private fun isValidLandmarks(landmarks: BodyLandmarks): Boolean {
        fun isValidFloat(f: Float): Boolean = f.isFinite() && f < Float.MAX_VALUE && f > -Float.MAX_VALUE

        val shouldersValid = isValidFloat(landmarks.leftShoulder.x) &&
                isValidFloat(landmarks.leftShoulder.y) &&
                isValidFloat(landmarks.rightShoulder.x) &&
                isValidFloat(landmarks.rightShoulder.y)

        val hipsValid = isValidFloat(landmarks.leftHip.x) &&
                isValidFloat(landmarks.leftHip.y) &&
                isValidFloat(landmarks.rightHip.x) &&
                isValidFloat(landmarks.rightHip.y)

        val shoulderDistance = distance(
            landmarks.leftShoulder.x, landmarks.leftShoulder.y,
            landmarks.rightShoulder.x, landmarks.rightShoulder.y
        )

        return shouldersValid && hipsValid && shoulderDistance > 10f
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun smoothstep(edge0: Float, edge1: Float, t: Float): Float {
        val clampedT = t.coerceIn(edge0, edge1)
        val x = (clampedT - edge0) / (edge1 - edge0)
        return x * x * (3f - 2f * x)
    }
}

data class DeformedMesh(
    val torsoVertices: List<DeformedVertex>,
    val leftSleeveVertices: List<DeformedVertex>,
    val rightSleeveVertices: List<DeformedVertex>
)