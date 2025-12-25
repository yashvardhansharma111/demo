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

        // ===================================================================
        // COMPUTE FABRIC SILHOUETTE POINTS (p0-p5) - SAME AS DEBUG OVERLAY
        // ===================================================================
        // Step 1: Compute body centers and directions
        val shoulderCenter = Offset(
            (bodyLandmarks.leftShoulder.x + bodyLandmarks.rightShoulder.x) * 0.5f,
            (bodyLandmarks.leftShoulder.y + bodyLandmarks.rightShoulder.y) * 0.5f
        )
        val hipCenter = Offset(
            (bodyLandmarks.leftHip.x + bodyLandmarks.rightHip.x) * 0.5f,
            (bodyLandmarks.leftHip.y + bodyLandmarks.rightHip.y) * 0.5f
        )
        
        // Body down direction (normalized)
        val downVec = Offset(hipCenter.x - shoulderCenter.x, hipCenter.y - shoulderCenter.y)
        val downLen = distance(downVec.x, downVec.y, 0f, 0f)
        val bodyDown = if (downLen > 0.001f) {
            Offset(downVec.x / downLen, downVec.y / downLen)
        } else {
            Offset(0f, 1f)  // Default: straight down
        }
        
        // Body right direction (perpendicular to down, pointing right)
        val bodyRight = Offset(-bodyDown.y, bodyDown.x)
        
        // Step 2: Compute reference dimensions
        val shoulderWidth = distance(
            bodyLandmarks.leftShoulder.x, bodyLandmarks.leftShoulder.y,
            bodyLandmarks.rightShoulder.x, bodyLandmarks.rightShoulder.y
        )
        val torsoHeight = distance(shoulderCenter.x, shoulderCenter.y, hipCenter.x, hipCenter.y)
        
        // Step 3: Compute offsets (SAME VALUES AS DEBUG OVERLAY)
        val shoulderOut = shoulderWidth * 0.82f
        val chestOut = shoulderWidth * 0.75f
        val hemOut = shoulderWidth * 0.9f
        
        val shoulderDrop = torsoHeight * 0.1f
        val chestDrop = torsoHeight * 0.3f
        val shoulderLift = torsoHeight * 0.15f  // Move p0, p1 upward above s1, s2
        
        // Step 4: Compute fabric silhouette points (p0-p5)
        val p0 = Offset(
            shoulderCenter.x - bodyRight.x * shoulderOut + bodyDown.x * shoulderDrop,
            shoulderCenter.y - bodyRight.y * shoulderOut + bodyDown.y * shoulderDrop - shoulderLift
        )
        val p1 = Offset(
            shoulderCenter.x + bodyRight.x * shoulderOut + bodyDown.x * shoulderDrop,
            shoulderCenter.y + bodyRight.y * shoulderOut + bodyDown.y * shoulderDrop - shoulderLift
        )
        
        val p2 = Offset(
            shoulderCenter.x - bodyRight.x * chestOut + bodyDown.x * chestDrop,
            shoulderCenter.y - bodyRight.y * chestOut + bodyDown.y * chestDrop
        )
        val p3 = Offset(
            shoulderCenter.x + bodyRight.x * chestOut + bodyDown.x * chestDrop,
            shoulderCenter.y + bodyRight.y * chestOut + bodyDown.y * chestDrop
        )
        
        val p4 = Offset(
            hipCenter.x - bodyRight.x * hemOut,
            hipCenter.y - bodyRight.y * hemOut
        )
        val p5 = Offset(
            hipCenter.x + bodyRight.x * hemOut,
            hipCenter.y + bodyRight.y * hemOut
        )

        // ===================================================================
        // DEFORM TORSO: Use fabric silhouette points (p0-p5)
        // ===================================================================
        val torsoVertices = mesh.torsoVertices.map { vertex ->
            deformVertexTorsoWithSilhouette(
                vertex,
                p0, p1, p2, p3, p4, p5,  // Use silhouette points
                screenWidth,
                screenHeight,
                metadata
            )
        }

        // Sleeves attach from p0 (left) and p1 (right) - fabric silhouette shoulder points
        // FIX: Swap p0 and p1 to fix left/right inversion
        val leftSleeveVertices = mesh.leftSleeveVertices.map { vertex ->
            deformVertexSleeve(
                vertex,
                bodyLandmarks,
                p1,  // Left sleeve attaches from p1 (swapped to fix inversion)
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
                p0,  // Right sleeve attaches from p0 (swapped to fix inversion)
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
     * Deforms a torso vertex using fabric silhouette points (p0-p5).
     * Mesh vertices are positioned ON the black silhouette points, not inside.
     * Uses 3-edge interpolation: top (p0-p1), mid (p2-p3), bottom (p4-p5).
     */
    private fun deformVertexTorsoWithSilhouette(
        vertex: MeshVertex,
        p0: Offset, p1: Offset, p2: Offset, p3: Offset, p4: Offset, p5: Offset,
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

        // Interpolate within fabric silhouette using 3 edges
        // Top edge: p0 → p1
        val topEdge = lerp(p0, p1, gx)
        // Mid edge: p2 → p3
        val midEdge = lerp(p2, p3, gx)
        // Bottom edge: p4 → p5
        val botEdge = lerp(p4, p5, gx)

        // Interpolate between edges based on gy
        val pos = if (gy < 0.5f) {
            // Top half: interpolate between top and mid
            lerp(topEdge, midEdge, gy * 2f)
        } else {
            // Bottom half: interpolate between mid and bottom
            lerp(midEdge, botEdge, (gy - 0.5f) * 2f)
        }

        val screenX = pos.x
        val screenY = pos.y

        // Convert screen coordinates to OpenGL NDC space
        // Screen: (0,0) top-left, (width,height) bottom-right
        // NDC: (-1,-1) bottom-left, (1,1) top-right
        // CRITICAL: Flip Y-axis because screen Y increases DOWN, NDC Y increases UP
        val ndcX = 2f * (screenX / screenWidth.toFloat()) - 1f
        val ndcY = 1f - 2f * (screenY / screenHeight.toFloat())

        // Diagnostic logging for corner vertices
        if (vertex.garmentX < 0.01f && vertex.garmentY < 0.01f) {
            Log.d("TORSO_DEBUG", """
                TOP-LEFT vertex (should match p0):
                UV: (${vertex.garmentX}, ${vertex.garmentY}) → (gx=$gx, gy=$gy)
                Screen: ($screenX, $screenY)
                NDC: ($ndcX, $ndcY)
                Expected p0: (${p0.x}, ${p0.y})
            """.trimIndent())
        }

        if (vertex.garmentX > 0.99f && vertex.garmentY < 0.01f) {
            Log.d("TORSO_DEBUG", """
                TOP-RIGHT vertex (should match p1):
                UV: (${vertex.garmentX}, ${vertex.garmentY}) → (gx=$gx, gy=$gy)
                Screen: ($screenX, $screenY)
                Expected p1: (${p1.x}, ${p1.y})
            """.trimIndent())
        }

        if (vertex.garmentX < 0.01f && vertex.garmentY > 0.99f) {
            Log.d("TORSO_DEBUG", """
                BOTTOM-LEFT vertex (should match p4):
                UV: (${vertex.garmentX}, ${vertex.garmentY}) → (gx=$gx, gy=$gy)
                Screen: ($screenX, $screenY)
                Expected p4: (${p4.x}, ${p4.y})
            """.trimIndent())
        }

        // Fix inverted texture: Flip V coordinate (vertical flip) to fix 180-degree inversion
        // Collar should be at top (shoulders), bottom should be at bottom (hips)
        val flippedV = 1.0f - vertex.v
        
        return DeformedVertex(
            screenX = ndcX,
            screenY = ndcY,
            u = vertex.u,
            v = flippedV  // Flip V to fix vertical inversion (collar at top, bottom at bottom)
        )
    }

    /**
     * Deforms a sleeve vertex using rotation-based approach.
     * Sleeve attaches from fabric silhouette shoulder point (p0 or p1) and rotates based on arm direction.
     */
    private fun deformVertexSleeve(
        vertex: MeshVertex,
        bodyLandmarks: BodyLandmarks,
        sleeveRoot: Offset,  // p0 (left) or p1 (right) - fabric silhouette shoulder point
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

        // Local sleeve space (origin at sleeve root, u=0.5 is center, v=0 is top)
        val localX = u - 0.5f  // -0.5 to +0.5 (left to right across sleeve)
        val localY = v         // 0 to 1 (top to bottom of sleeve)

        // Sleeve attaches from fabric silhouette point (p0 or p1)
        val shoulder = sleeveRoot  // Use p0 (left) or p1 (right) from fabric silhouette

        val elbow = if (isLeftSleeve) {
            bodyLandmarks.leftElbow
        } else {
            bodyLandmarks.rightElbow
        }

        val wrist = if (isLeftSleeve) {
            bodyLandmarks.leftWrist
        } else {
            bodyLandmarks.rightWrist
        }

        // Calculate arm direction (sleeve root to wrist for full arm rotation)
        val dx = wrist.x - shoulder.x
        val dy = wrist.y - shoulder.y
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

    private fun lerp(a: Offset, b: Offset, t: Float): Offset {
        return Offset(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t
        )
    }
}

data class DeformedMesh(
    val torsoVertices: List<DeformedVertex>,
    val leftSleeveVertices: List<DeformedVertex>,
    val rightSleeveVertices: List<DeformedVertex>
)