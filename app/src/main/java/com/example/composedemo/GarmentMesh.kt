package com.example.composedemo

import kotlin.math.sqrt

/**
 * Represents a single vertex in the garment mesh.
 * All coordinates are in normalized garment space (0.0 - 1.0).
 */
data class MeshVertex(
    val garmentX: Float,      // X in garment space (0-1)
    val garmentY: Float,      // Y in garment space (0-1)
    val u: Float,             // UV coordinate X (same as garmentX)
    val v: Float,             // UV coordinate Y (same as garmentY)
    val boneWeights: BoneWeights // Weights for weighted skinning
)

/**
 * Bone weights for weighted skinning.
 * Weights sum to 1.0 and determine influence of each anchor point.
 */
data class BoneWeights(
    val leftShoulder: Float,
    val rightShoulder: Float,
    val leftElbow: Float,
    val rightElbow: Float,
    val leftWrist: Float,
    val rightWrist: Float,
    val leftHip: Float,
    val rightHip: Float
)

/**
 * Complete mesh for a garment, containing vertices for torso and sleeves.
 */
data class GarmentMesh(
    val torsoVertices: List<MeshVertex>,
    val leftSleeveVertices: List<MeshVertex>,
    val rightSleeveVertices: List<MeshVertex>
)

/**
 * Generates 2D grid meshes for garment regions based on metadata.
 */
object GarmentMeshGenerator {
    /**
     * Generates complete mesh for a garment from metadata.
     * Mesh is precomputed once and reused for all frames.
     */
    fun generateMesh(metadata: GarmentMetadata): GarmentMesh {
        val torsoVertices = generateTorsoMesh(metadata)
        val leftSleeveVertices = generateSleeveMesh(
            metadata.regions.leftSleeve,
            metadata.anchors,
            metadata.mesh.sleeveGrid,
            isLeft = true
        )
        val rightSleeveVertices = generateSleeveMesh(
            metadata.regions.rightSleeve,
            metadata.anchors,
            metadata.mesh.sleeveGrid,
            isLeft = false
        )

        return GarmentMesh(
            torsoVertices = torsoVertices,
            leftSleeveVertices = leftSleeveVertices,
            rightSleeveVertices = rightSleeveVertices
        )
    }

    /**
     * Generates grid mesh for torso region using bilinear interpolation.
     * Torso is a quad defined by leftShoulder, rightShoulder, leftHip, rightHip.
     */
    private fun generateTorsoMesh(metadata: GarmentMetadata): List<MeshVertex> {
        val gridWidth = metadata.mesh.torsoGrid[0]
        val gridHeight = metadata.mesh.torsoGrid[1]
        val bounds = metadata.regions.torso.bounds
        val anchors = metadata.anchors

        val vertices = mutableListOf<MeshVertex>()

        // Generate grid vertices within torso bounds
        for (j in 0 until gridHeight) {
            for (i in 0 until gridWidth) {
                // Normalized position within torso region (0-1)
                val u = i / (gridWidth - 1).toFloat()
                val v = j / (gridHeight - 1).toFloat()

                // Map to garment space
                val garmentX = bounds[0] + (bounds[2] - bounds[0]) * u
                val garmentY = bounds[1] + (bounds[3] - bounds[1]) * v

                // Compute bone weights based on distance to anchors
                val boneWeights = computeTorsoBoneWeights(
                    garmentX, garmentY, anchors
                )

                vertices.add(
                    MeshVertex(
                        garmentX = garmentX,
                        garmentY = garmentY,
                        u = garmentX, // UV = garment space coordinates
                        v = garmentY,
                        boneWeights = boneWeights
                    )
                )
            }
        }

        return vertices
    }

    /**
     * Generates grid mesh for sleeve region.
     * Sleeve follows arm chain: shoulder → elbow → wrist.
     */
    private fun generateSleeveMesh(
        regionBounds: RegionBounds,
        anchors: AnchorPoints,
        gridSize: IntArray,
        isLeft: Boolean
    ): List<MeshVertex> {
        val gridWidth = gridSize[0]
        val gridHeight = gridSize[1]
        val bounds = regionBounds.bounds

        val vertices = mutableListOf<MeshVertex>()

        // Select appropriate anchors based on side
        val shoulder = if (isLeft) anchors.leftShoulder else anchors.rightShoulder
        val elbow = if (isLeft) anchors.leftElbow else anchors.rightElbow
        val wrist = if (isLeft) anchors.leftWrist else anchors.rightWrist

        for (j in 0 until gridHeight) {
            for (i in 0 until gridWidth) {
                val u = i / (gridWidth - 1).toFloat()
                val v = j / (gridHeight - 1).toFloat()

                // Map to garment space
                val garmentX = bounds[0] + (bounds[2] - bounds[0]) * u
                val garmentY = bounds[1] + (bounds[3] - bounds[1]) * v

                // Compute bone weights for arm chain
                val boneWeights = computeSleeveBoneWeights(
                    garmentX, garmentY, shoulder, elbow, wrist, isLeft
                )

                vertices.add(
                    MeshVertex(
                        garmentX = garmentX,
                        garmentY = garmentY,
                        u = garmentX,
                        v = garmentY,
                        boneWeights = boneWeights
                    )
                )
            }
        }

        return vertices
    }

    /**
     * Computes bone weights for torso vertices using inverse distance weighting.
     * Closer anchors have higher influence.
     */
    private fun computeTorsoBoneWeights(
        x: Float, y: Float, anchors: AnchorPoints
    ): BoneWeights {
        // Compute distances to all relevant anchors
        val distLS = distance(x, y, anchors.leftShoulder.x, anchors.leftShoulder.y)
        val distRS = distance(x, y, anchors.rightShoulder.x, anchors.rightShoulder.y)
        val distLH = distance(x, y, anchors.leftHip.x, anchors.leftHip.y)
        val distRH = distance(x, y, anchors.rightHip.x, anchors.rightHip.y)

        // Use inverse distance weighting (closer = higher weight)
        // Add small epsilon to avoid division by zero
        val epsilon = 0.001f
        val wLS = 1.0f / (distLS + epsilon)
        val wRS = 1.0f / (distRS + epsilon)
        val wLH = 1.0f / (distLH + epsilon)
        val wRH = 1.0f / (distRH + epsilon)

        val total = wLS + wRS + wLH + wRH

        return BoneWeights(
            leftShoulder = wLS / total,
            rightShoulder = wRS / total,
            leftElbow = 0f,
            rightElbow = 0f,
            leftWrist = 0f,
            rightWrist = 0f,
            leftHip = wLH / total,
            rightHip = wRH / total
        )
    }

    /**
     * Computes bone weights for sleeve vertices.
     * Sleeves primarily follow arm chain (shoulder → elbow → wrist).
     */
    private fun computeSleeveBoneWeights(
        x: Float, y: Float,
        shoulder: Point2D,
        elbow: Point2D,
        wrist: Point2D,
        isLeft: Boolean
    ): BoneWeights {
        // Compute distances along arm chain
        val distShoulder = distance(x, y, shoulder.x, shoulder.y)
        val distElbow = distance(x, y, elbow.x, elbow.y)
        val distWrist = distance(x, y, wrist.x, wrist.y)

        val epsilon = 0.001f
        val wShoulder = 1.0f / (distShoulder + epsilon)
        val wElbow = 1.0f / (distElbow + epsilon)
        val wWrist = 1.0f / (distWrist + epsilon)

        val total = wShoulder + wElbow + wWrist

        return if (isLeft) {
            BoneWeights(
                leftShoulder = wShoulder / total,
                rightShoulder = 0f,
                leftElbow = wElbow / total,
                rightElbow = 0f,
                leftWrist = wWrist / total,
                rightWrist = 0f,
                leftHip = 0f,
                rightHip = 0f
            )
        } else {
            BoneWeights(
                leftShoulder = 0f,
                rightShoulder = wShoulder / total,
                leftElbow = 0f,
                rightElbow = wElbow / total,
                leftWrist = 0f,
                rightWrist = wWrist / total,
                leftHip = 0f,
                rightHip = 0f
            )
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}

