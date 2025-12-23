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

        // ===============================
        // CORRECT CLOTHING SHOULDER LOGIC
        // ===============================
        // Shoulders are NOT on the torso center line - they are horizontally outside the chest line
        // Mental model: imaginary shoulder line (OUTSIDE chest), not at joint centers
        
        // LAYER 1: Skeleton joints (MediaPipe)
        // These define WHERE THE BODY IS, not where fabric attaches
        
        // LAYER 2: Fabric envelope (computed from skeleton)
        // Compute chest line height (slightly below neck, not mid torso)
        // Use shoulder Y as reference - this is the chest/neck region
        val chestY = (bodyLandmarks.leftShoulder.y + bodyLandmarks.rightShoulder.y) * 0.5f
        
        // Compute chest width (distance between shoulder joints)
        // This is the shoulder span - the actual body width
        val shoulderSpan = distance(
            bodyLandmarks.leftShoulder.x, bodyLandmarks.leftShoulder.y,
            bodyLandmarks.rightShoulder.x, bodyLandmarks.rightShoulder.y
        )
        
        // Outward expansion (THIS makes shirt broad and worn-looking)
        // Push horizontally outward from shoulder joints
        // 40-50% of shoulder span looks natural (using 45%)
        // This creates the "fabric envelope" - the outer surface of the garment
        val outwardOffset = shoulderSpan * 0.45f
        
        // Small vertical drop for cloth seam
        // Shirt seam sits slightly below shoulder joint (12% of span)
        // Creates gap between neck and shoulder seam
        val verticalDrop = shoulderSpan * 0.12f
        
        // FINAL imagined clothing shoulders (OUTSIDE chest)
        // These are NOT skeleton points - these are fabric attachment points
        // Left shoulder: move left (subtract X from joint), drop down (add Y from joint)
        val adjustedLeftShoulder = Offset(
            bodyLandmarks.leftShoulder.x - outwardOffset,
            chestY + verticalDrop
        )
        
        // Right shoulder: move right (add X from joint), drop down (add Y from joint)
        val adjustedRightShoulder = Offset(
            bodyLandmarks.rightShoulder.x + outwardOffset,
            chestY + verticalDrop
        )
        
        // Create adjusted landmarks for torso (use adjusted shoulders as fabric seams)
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
        
        // Compute body reference size for scaling (using adjusted shoulders)
        val shoulderWidth = distance(
            adjustedLeftShoulder.x, adjustedLeftShoulder.y,
            adjustedRightShoulder.x, adjustedRightShoulder.y
        )
        
        // Compute torso height for scaling
        val torsoHeight = distance(
            (adjustedLeftShoulder.x + adjustedRightShoulder.x) / 2f,
            (adjustedLeftShoulder.y + adjustedRightShoulder.y) / 2f,
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
        // LAYER 3: Garment mesh (torso uses adjusted fabric seams, sleeves attach to them)
        
        // Torso uses adjusted landmarks (with dropped/pushed shoulders as outer seams)
        val torsoVertices = mesh.torsoVertices.map { vertex ->
            deformVertexTorso(
                vertex,
                adjustedLandmarks,
                scaleX,
                scaleY,
                screenWidth,
                screenHeight,
                metadata
            )
        }

        // Sleeves use original landmarks for arm tracking
        // But will attach to torso quad edge at adjusted seam
        val leftSleeveVertices = mesh.leftSleeveVertices.map { vertex ->
            deformVertexSleeve(
                vertex,
                bodyLandmarks,
                adjustedLandmarks,
                scaleX,
                scaleY,
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
                adjustedLandmarks,
                scaleX,
                scaleY,
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
     * Deforms a torso vertex using bilinear quad interpolation.
     * Formula: finalPosition = bilinear interpolation within quad
     *   (adjustedLeftShoulder, adjustedRightShoulder, adjustedLeftHip, adjustedRightHip)
     * 
     * The quad wraps around the body - it does NOT use bones, but fabric seams.
     * CRITICAL: Converts screen coordinates to OpenGL NDC space (-1 to +1).
     */
    private fun deformVertexTorso(
        vertex: MeshVertex,
        adjustedLandmarks: BodyLandmarks,
        scaleX: Float,
        scaleY: Float,
        screenWidth: Int,
        screenHeight: Int,
        metadata: GarmentMetadata
    ): DeformedVertex {
        // Remap garment UVs to torso region space (0-1 within torso bounds)
        val torsoBounds = metadata.regions.torso.bounds
        // bounds = [minX, minY, maxX, maxY]
        val torsoWidth = torsoBounds[2] - torsoBounds[0]
        val torsoHeight = torsoBounds[3] - torsoBounds[1]
        
        // Remap garment coordinates to torso region space (0-1 within torso)
        val gx = if (torsoWidth > 0.001f) {
            (vertex.garmentX - torsoBounds[0]) / torsoWidth
        } else {
            0.5f  // Fallback if bounds are invalid
        }
        val gy = if (torsoHeight > 0.001f) {
            (vertex.garmentY - torsoBounds[1]) / torsoHeight
        } else {
            0.5f  // Fallback if bounds are invalid
        }
        
        // Bilinear interpolation within the quad
        // Quad vertices are the ADJUSTED fabric seams, not skeleton joints
        //
        // (adjustedLeftShoulder)  ───── (adjustedRightShoulder)
        //         |                            |
        //         |      TORSO QUAD            |
        //         |                            |
        // (leftHip)           ───── (rightHip)
        
        // 1️⃣ Horizontal interpolation at top edge (shoulder seam)
        // topEdge = lerp(adjustedLeftShoulder, adjustedRightShoulder, gx)
        val topEdgeX = adjustedLandmarks.leftShoulder.x + 
                       (adjustedLandmarks.rightShoulder.x - adjustedLandmarks.leftShoulder.x) * gx
        val topEdgeY = adjustedLandmarks.leftShoulder.y + 
                       (adjustedLandmarks.rightShoulder.y - adjustedLandmarks.leftShoulder.y) * gx
        
        // 2️⃣ Horizontal interpolation at bottom edge (hip line)
        // bottomEdge = lerp(leftHip, rightHip, gx)
        val bottomEdgeX = adjustedLandmarks.leftHip.x + 
                          (adjustedLandmarks.rightHip.x - adjustedLandmarks.leftHip.x) * gx
        val bottomEdgeY = adjustedLandmarks.leftHip.y + 
                          (adjustedLandmarks.rightHip.y - adjustedLandmarks.leftHip.y) * gx
        
        // 3️⃣ Vertical interpolation (from top to bottom)
        // finalPosition = lerp(topEdge, bottomEdge, gy)
        val screenX = topEdgeX + (bottomEdgeX - topEdgeX) * gy
        val screenY = topEdgeY + (bottomEdgeY - topEdgeY) * gy
        
        // Convert to OpenGL NDC space immediately
        // OpenGL NDC: (-1,-1) is bottom-left, (1,1) is top-right
        // Screen: (0,0) is top-left, (width,height) is bottom-right
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
     * Deforms a sleeve vertex using continuous arm frame with attachment blending.
     * 
     * Key insight: Sleeves are tubes around the arm, not flat panels.
     * - Upper part of sleeve: attached to torso outer edge (adjusted seam)
     * - Middle part: smooth blend from torso attachment to elbow
     * - Lower part: follows arm motion (shoulder → elbow → wrist)
     * 
     * Uses two sets of landmarks:
     * - bodyLandmarks: original skeleton joints for arm rotation
     * - adjustedLandmarks: fabric seams for torso attachment point
     * 
     * CRITICAL: Converts screen coordinates to OpenGL NDC space (-1 to +1).
     */
    private fun deformVertexSleeve(
        vertex: MeshVertex,
        bodyLandmarks: BodyLandmarks,
        adjustedLandmarks: BodyLandmarks,
        scaleX: Float,
        scaleY: Float,
        isLeftSleeve: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        metadata: GarmentMetadata
    ): DeformedVertex {
        // Remap sleeve UVs to sleeve region space (0-1 within sleeve bounds)
        val sleeveBounds = if (isLeftSleeve) {
            metadata.regions.leftSleeve.bounds
        } else {
            metadata.regions.rightSleeve.bounds
        }
        // bounds = [minX, minY, maxX, maxY]
        val sleeveWidth = sleeveBounds[2] - sleeveBounds[0]
        val sleeveHeight = sleeveBounds[3] - sleeveBounds[1]
        
        // Remap garment coordinates to sleeve region space (0-1 within sleeve)
        val sleeveU = if (sleeveWidth > 0.001f) {
            (vertex.garmentX - sleeveBounds[0]) / sleeveWidth
        } else {
            0.5f
        }
        val sleeveV = if (sleeveHeight > 0.001f) {
            (vertex.garmentY - sleeveBounds[1]) / sleeveHeight
        } else {
            0.5f
        }
        
        // Convert remapped UVs to local coordinates
        // localX: -0.5 to +0.5 (left to right across sleeve)
        // localY: -0.5 to +0.5 (bottom to top of sleeve)
        val localX = sleeveU - 0.5f
        val localY = 0.5f - sleeveV  // Invert Y for proper orientation
        
        // Map sleeve garment Y → torso V (for attachment point on torso edge)
        // sleeveV is sleeve-local, but attachment needs torso-relative position
        val torsoBounds = metadata.regions.torso.bounds
        val torsoMinY = torsoBounds[1]
        val torsoMaxY = torsoBounds[3]
        val torsoHeight = torsoMaxY - torsoMinY
        
        // Map global garment Y to torso-relative V
        val torsoV = if (torsoHeight > 0.001f) {
            (vertex.garmentY - torsoMinY) / torsoHeight
        } else {
            0.5f  // Fallback
        }
        // Clamp to [0,1] to handle sleeves that extend beyond torso bounds
        val clampedTorsoV = torsoV.coerceIn(0f, 1f)
        
        // Get arm landmarks (use original skeleton joints, not adjusted seams)
        val shoulderX = if (isLeftSleeve) bodyLandmarks.leftShoulder.x else bodyLandmarks.rightShoulder.x
        val shoulderY = if (isLeftSleeve) bodyLandmarks.leftShoulder.y else bodyLandmarks.rightShoulder.y
        val elbowX = if (isLeftSleeve) bodyLandmarks.leftElbow.x else bodyLandmarks.rightElbow.x
        val elbowY = if (isLeftSleeve) bodyLandmarks.leftElbow.y else bodyLandmarks.rightElbow.y
        val wristX = if (isLeftSleeve) bodyLandmarks.leftWrist.x else bodyLandmarks.rightWrist.x
        val wristY = if (isLeftSleeve) bodyLandmarks.leftWrist.y else bodyLandmarks.rightWrist.y

        // Compute arm radius for sleeve width
        // Use full arm length (shoulder to wrist) for consistent radius
        val fullArmLength = distance(shoulderX, shoulderY, wristX, wristY)
        val sleeveRadius = fullArmLength * 0.18f  // Tweak 0.15-0.22 for different sleeve thickness

        // Attach sleeve to torso quad edge using torsoV
        // This is where the sleeve connects to the torso at the fabric seam
        val torsoAttachX: Float
        val torsoAttachY: Float
        if (isLeftSleeve) {
            // Left sleeve: attach to left side of quad (adjustedLeftShoulder → leftHip)
            torsoAttachX = adjustedLandmarks.leftShoulder.x + 
                           (adjustedLandmarks.leftHip.x - adjustedLandmarks.leftShoulder.x) * clampedTorsoV
            torsoAttachY = adjustedLandmarks.leftShoulder.y + 
                           (adjustedLandmarks.leftHip.y - adjustedLandmarks.leftShoulder.y) * clampedTorsoV
        } else {
            // Right sleeve: attach to right side of quad (adjustedRightShoulder → rightHip)
            torsoAttachX = adjustedLandmarks.rightShoulder.x + 
                           (adjustedLandmarks.rightHip.x - adjustedLandmarks.rightShoulder.x) * clampedTorsoV
            torsoAttachY = adjustedLandmarks.rightShoulder.y + 
                           (adjustedLandmarks.rightHip.y - adjustedLandmarks.rightShoulder.y) * clampedTorsoV
        }

        // Continuous arm frame with smooth blending
        // Use sleeveV (0 at shoulder, 1 at wrist) for blending
        // Blend between torso attachment (upper) and elbow (lower) for smooth transition
        val blend = smoothstep(0.4f, 0.6f, sleeveV)  // Smooth transition between 40% and 60% down sleeve
        
        // Upper origin: torso edge attachment point (where sleeve is stitched)
        val upperOriginX = torsoAttachX
        val upperOriginY = torsoAttachY
        
        // Lower origin: elbow joint (arm bends here)
        val lowerOriginX = elbowX
        val lowerOriginY = elbowY
        
        // Blended origin: smooth transition from torso edge to elbow
        // Creates natural fabric flow without seam breaks
        val regionOriginX = upperOriginX + (lowerOriginX - upperOriginX) * blend
        val regionOriginY = upperOriginY + (lowerOriginY - upperOriginY) * blend

        // Single continuous arm axis (shoulder → wrist)
        // This ensures sleeves bend naturally without seam breaks
        val armAxisX = wristX - shoulderX
        val armAxisY = wristY - shoulderY
        val armAxisLen = kotlin.math.sqrt(armAxisX * armAxisX + armAxisY * armAxisY)
        
        // Normalize arm axis for down direction
        val regionDownX = if (armAxisLen > 0.001f) armAxisX / armAxisLen else 0f
        val regionDownY = if (armAxisLen > 0.001f) armAxisY / armAxisLen else 1f

        // Arm right vector: perpendicular to arm axis, pointing outward from body
        // For left sleeve: right = rotate(down, -90°) = (-downY, downX)
        // For right sleeve: right = rotate(down, +90°) = (downY, -downX)
        val regionRightX = if (isLeftSleeve) -regionDownY else regionDownY
        val regionRightY = if (isLeftSleeve) regionDownX else -regionDownX

        // Apply scaled offset: Project garment-space offset into region-local space
        // Use arm radius for sleeve width, scaleY for length
        val offsetX = regionRightX * localX * sleeveRadius + regionDownX * localY * scaleY
        val offsetY = regionRightY * localX * sleeveRadius + regionDownY * localY * scaleY

        // Final vertex position: regionOrigin + offset
        val screenX = regionOriginX + offsetX
        val screenY = regionOriginY + offsetY

        // Convert screen coordinates to OpenGL NDC space (-1 to +1)
        // OpenGL NDC: (-1,-1) is bottom-left, (1,1) is top-right
        // Screen: (0,0) is top-left, (width,height) is bottom-right
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
    
    /**
     * Smoothstep function for blending between two values.
     * Returns 0 for t <= edge0, 1 for t >= edge1, smooth curve in between.
     * Used for continuous sleeve origin blending.
     */
    private fun smoothstep(edge0: Float, edge1: Float, t: Float): Float {
        val clampedT = t.coerceIn(edge0, edge1)
        val x = (clampedT - edge0) / (edge1 - edge0)
        return x * x * (3f - 2f * x)  // Hermite interpolation
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