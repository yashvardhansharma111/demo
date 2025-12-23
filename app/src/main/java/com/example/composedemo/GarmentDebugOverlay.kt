package com.example.composedemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import android.util.Log

/**
 * DEBUG OVERLAY: Visual debugging for garment deformation pipeline.
 * Draws debug layers in this order:
 * 1. MediaPipe body landmarks (RED dots) - Layer 1
 * 2. Garment anchor points (GREEN dots) - Layer 2
 * 3. Torso quad edges (YELLOW lines) - Layer 3
 * 4. Deformed mesh vertices (BLUE dots) - Layer 4
 */
@Composable
fun GarmentDebugOverlay(
    bodyLandmarks: BodyLandmarks,
    metadata: GarmentMetadata,
    deformedMesh: DeformedMesh,
    screenWidth: Int,
    screenHeight: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val paint = androidx.compose.ui.graphics.Paint().apply {
            isAntiAlias = true
        }
        
        // Helper function to draw a point
        fun drawPoint(offset: Offset, color: Color, radius: Float = 8f) {
            drawCircle(center = offset, radius = radius, color = color)
        }
        
        // Helper function to draw a line
        fun drawLineHelper(start: Offset, end: Offset, color: Color, strokeWidth: Float = 3f) {
            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth)
        }
        
        // 1️⃣ LAYER 1: MediaPipe body landmarks (RED dots)
        // This tells you if pose itself is correct
        // Should see dots exactly on your shoulders, elbows, hips
        drawPoint(bodyLandmarks.leftShoulder, Color.Red)
        drawPoint(bodyLandmarks.rightShoulder, Color.Red)
        drawPoint(bodyLandmarks.leftElbow, Color.Red)
        drawPoint(bodyLandmarks.rightElbow, Color.Red)
        drawPoint(bodyLandmarks.leftHip, Color.Red)
        drawPoint(bodyLandmarks.rightHip, Color.Red)
        
        // 2️⃣ LAYER 2: Garment anchor points (GREEN dots)
        // This shows if metadata → body mapping makes sense
        // Map garment anchor points to body space using bilinear interpolation
        fun mapAnchorToBody(anchor: Point2D): Offset {
            // For torso anchors, use quad interpolation
            val topX = bodyLandmarks.leftShoulder.x + 
                      (bodyLandmarks.rightShoulder.x - bodyLandmarks.leftShoulder.x) * anchor.x
            val topY = bodyLandmarks.leftShoulder.y + 
                      (bodyLandmarks.rightShoulder.y - bodyLandmarks.leftShoulder.y) * anchor.x
            val bottomX = bodyLandmarks.leftHip.x + 
                         (bodyLandmarks.rightHip.x - bodyLandmarks.leftHip.x) * anchor.x
            val bottomY = bodyLandmarks.leftHip.y + 
                         (bodyLandmarks.rightHip.y - bodyLandmarks.leftHip.y) * anchor.x
            
            val x = topX + (bottomX - topX) * anchor.y
            val y = topY + (bottomY - topY) * anchor.y
            return Offset(x, y)
        }
        
        drawPoint(mapAnchorToBody(metadata.anchors.leftShoulder), Color.Green)
        drawPoint(mapAnchorToBody(metadata.anchors.rightShoulder), Color.Green)
        drawPoint(mapAnchorToBody(metadata.anchors.leftElbow), Color.Green)
        drawPoint(mapAnchorToBody(metadata.anchors.rightElbow), Color.Green)
        drawPoint(mapAnchorToBody(metadata.anchors.leftHip), Color.Green)
        drawPoint(mapAnchorToBody(metadata.anchors.rightHip), Color.Green)
        
        // 3️⃣ LAYER 3: Torso quad edges (YELLOW lines)
        // This is critical and will immediately reveal the core issue
        // Draw quad: LS ──── RS
        //            │        │
        //            │ TORSO  │
        //            │        │
        //            LH ──── RH
        val ls = bodyLandmarks.leftShoulder
        val rs = bodyLandmarks.rightShoulder
        val lh = bodyLandmarks.leftHip
        val rh = bodyLandmarks.rightHip
        
        drawLineHelper(ls, rs, Color.Yellow)  // Top edge: LS ──── RS
        drawLineHelper(rs, rh, Color.Yellow)  // Right edge: RS ──── RH
        drawLineHelper(rh, lh, Color.Yellow)  // Bottom edge: RH ──── LH
        drawLineHelper(lh, ls, Color.Yellow)   // Left edge: LH ──── LS
        
        // 4️⃣ LAYER 4: Deformed mesh vertices (BLUE dots)
        // Draw torso vertices (convert from NDC back to screen space for visualization)
        fun ndcToScreen(ndcX: Float, ndcY: Float): Offset {
            val x = (ndcX + 1f) / 2f * screenWidth
            val y = (1f - ndcY) / 2f * screenHeight  // Flip Y back
            return Offset(x, y)
        }
        
        // Draw torso vertices
        deformedMesh.torsoVertices.forEach { vertex ->
            val screenPos = ndcToScreen(vertex.screenX, vertex.screenY)
            drawPoint(screenPos, Color.Blue, radius = 4f)
        }
        
        // Draw sleeve vertices
        deformedMesh.leftSleeveVertices.forEach { vertex ->
            val screenPos = ndcToScreen(vertex.screenX, vertex.screenY)
            drawPoint(screenPos, Color.Blue, radius = 4f)
        }
        
        deformedMesh.rightSleeveVertices.forEach { vertex ->
            val screenPos = ndcToScreen(vertex.screenX, vertex.screenY)
            drawPoint(screenPos, Color.Blue, radius = 4f)
        }
        
        // QUICK SANITY CHECK: Log first torso vertex garment coordinates
        if (deformedMesh.torsoVertices.isNotEmpty()) {
            // Log vertex count for debugging
            Log.d("DEBUG_OVERLAY", 
                "Drawing ${deformedMesh.torsoVertices.size} torso vertices, " +
                "${deformedMesh.leftSleeveVertices.size} left sleeve vertices, " +
                "${deformedMesh.rightSleeveVertices.size} right sleeve vertices")
        }
    }
}

