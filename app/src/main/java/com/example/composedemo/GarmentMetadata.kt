package com.example.composedemo

import android.content.Context
import org.json.JSONObject
import java.io.InputStream

/**
 * Garment metadata defining anchors, regions, mesh resolution, and fit factors.
 * All coordinates are in normalized garment space (0.0 - 1.0).
 */
data class GarmentMetadata(
    val id: String,
    val type: String,
    val garmentSpace: GarmentSpace,
    val anchors: AnchorPoints,
    val regions: GarmentRegions,
    val mesh: MeshConfig,
    val fit: FitFactors
)

data class GarmentSpace(
    val width: Float,
    val height: Float
)

data class AnchorPoints(
    val leftShoulder: Point2D,
    val rightShoulder: Point2D,
    val leftElbow: Point2D,
    val rightElbow: Point2D,
    val leftWrist: Point2D,
    val rightWrist: Point2D,
    val leftHip: Point2D,
    val rightHip: Point2D
)

data class Point2D(
    val x: Float,
    val y: Float
)

data class GarmentRegions(
    val torso: RegionBounds,
    val leftSleeve: RegionBounds,
    val rightSleeve: RegionBounds
)

data class RegionBounds(
    val bounds: FloatArray // [minX, minY, maxX, maxY] in garment space
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RegionBounds
        return bounds.contentEquals(other.bounds)
    }

    override fun hashCode(): Int {
        return bounds.contentHashCode()
    }
}

data class MeshConfig(
    val torsoGrid: IntArray, // [width, height] in vertices
    val sleeveGrid: IntArray  // [width, height] in vertices
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshConfig
        return torsoGrid.contentEquals(other.torsoGrid) &&
                sleeveGrid.contentEquals(other.sleeveGrid)
    }

    override fun hashCode(): Int {
        return torsoGrid.contentHashCode() * 31 + sleeveGrid.contentHashCode()
    }
}

data class FitFactors(
    val widthFactor: Float,
    val heightFactor: Float
)

/**
 * Parses garment metadata from JSON file in assets.
 */
object GarmentMetadataLoader {
    fun loadFromAssets(context: Context, filename: String): GarmentMetadata {
        val inputStream: InputStream = context.assets.open(filename)
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        return parseFromJson(jsonString)
    }

    private fun parseFromJson(jsonString: String): GarmentMetadata {
        val json = JSONObject(jsonString)

        val garmentSpace = GarmentSpace(
            width = json.getJSONObject("garmentSpace").getDouble("width").toFloat(),
            height = json.getJSONObject("garmentSpace").getDouble("height").toFloat()
        )

        val anchorsJson = json.getJSONObject("anchors")
        val anchors = AnchorPoints(
            leftShoulder = parsePoint(anchorsJson.getJSONArray("leftShoulder")),
            rightShoulder = parsePoint(anchorsJson.getJSONArray("rightShoulder")),
            leftElbow = parsePoint(anchorsJson.getJSONArray("leftElbow")),
            rightElbow = parsePoint(anchorsJson.getJSONArray("rightElbow")),
            leftWrist = parsePoint(anchorsJson.getJSONArray("leftWrist")),
            rightWrist = parsePoint(anchorsJson.getJSONArray("rightWrist")),
            leftHip = parsePoint(anchorsJson.getJSONArray("leftHip")),
            rightHip = parsePoint(anchorsJson.getJSONArray("rightHip"))
        )

        val regionsJson = json.getJSONObject("regions")
        val regions = GarmentRegions(
            torso = parseRegionBounds(regionsJson.getJSONObject("torso")),
            leftSleeve = parseRegionBounds(regionsJson.getJSONObject("leftSleeve")),
            rightSleeve = parseRegionBounds(regionsJson.getJSONObject("rightSleeve"))
        )

        val meshJson = json.getJSONObject("mesh")
        val mesh = MeshConfig(
            torsoGrid = intArrayOf(
                meshJson.getJSONArray("torsoGrid").getInt(0),
                meshJson.getJSONArray("torsoGrid").getInt(1)
            ),
            sleeveGrid = intArrayOf(
                meshJson.getJSONArray("sleeveGrid").getInt(0),
                meshJson.getJSONArray("sleeveGrid").getInt(1)
            )
        )

        val fitJson = json.getJSONObject("fit")
        val fit = FitFactors(
            widthFactor = fitJson.getDouble("widthFactor").toFloat(),
            heightFactor = fitJson.getDouble("heightFactor").toFloat()
        )

        return GarmentMetadata(
            id = json.getString("id"),
            type = json.getString("type"),
            garmentSpace = garmentSpace,
            anchors = anchors,
            regions = regions,
            mesh = mesh,
            fit = fit
        )
    }

    private fun parsePoint(array: org.json.JSONArray): Point2D {
        return Point2D(
            x = array.getDouble(0).toFloat(),
            y = array.getDouble(1).toFloat()
        )
    }

    private fun parseRegionBounds(obj: JSONObject): RegionBounds {
        val boundsArray = obj.getJSONArray("bounds")
        return RegionBounds(
            floatArrayOf(
                boundsArray.getDouble(0).toFloat(), // minX
                boundsArray.getDouble(1).toFloat(), // minY
                boundsArray.getDouble(2).toFloat(), // maxX
                boundsArray.getDouble(3).toFloat()  // maxY
            )
        )
    }
}

