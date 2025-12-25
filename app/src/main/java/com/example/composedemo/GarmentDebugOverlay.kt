package com.example.composedemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.sqrt

/**
 * DEBUG OVERLAY: Shows MediaPipe body landmarks as RED dots (skeleton)
 * and fabric silhouette points as BLACK dots (shirt outline).
 */
@Composable
fun GarmentDebugOverlay(
    bodyLandmarks: BodyLandmarks,
    screenWidth: Int,
    screenHeight: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Helper function to draw a point
        fun drawPoint(offset: Offset, color: Color, radius: Float = 10f) {
            drawCircle(center = offset, radius = radius, color = color)
        }
        
        // Helper function to draw text label
        fun DrawScope.drawLabel(offset: Offset, text: String) {
            val paint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 40f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText(
                text,
                offset.x,
                offset.y - 20f, // Position text above the point
                paint
            )
        }
        
        // Helper function to compute distance
        fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }
        
        // Helper function to normalize vector
        fun normalize(vec: Offset): Offset {
            val len = distance(vec.x, vec.y, 0f, 0f)
            return if (len > 0.001f) {
                Offset(vec.x / len, vec.y / len)
            } else {
                Offset(0f, 1f) // Default: straight down
            }
        }
        
        // ===================================================================
        // RED DOTS: MediaPipe body landmarks (skeleton) - UNCHANGED
        // ===================================================================
        // Shoulders
        drawPoint(bodyLandmarks.leftShoulder, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.leftShoulder, "s1")
        
        drawPoint(bodyLandmarks.rightShoulder, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.rightShoulder, "s2")
        
        // Elbows
        drawPoint(bodyLandmarks.leftElbow, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.leftElbow, "e1")
        
        drawPoint(bodyLandmarks.rightElbow, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.rightElbow, "e2")
        
        // Wrists
        drawPoint(bodyLandmarks.leftWrist, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.leftWrist, "w1")
        
        drawPoint(bodyLandmarks.rightWrist, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.rightWrist, "w2")
        
        // Hips
        drawPoint(bodyLandmarks.leftHip, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.leftHip, "h1")
        
        drawPoint(bodyLandmarks.rightHip, Color.Red, radius = 12f)
        drawLabel(bodyLandmarks.rightHip, "h2")
        
        // ===================================================================
        // BLACK DOTS: Fabric silhouette points (shirt outline)
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
        val bodyDown = normalize(downVec)
        
        // Body right direction (perpendicular to down, pointing right)
        val bodyRight = Offset(-bodyDown.y, bodyDown.x)
        
        // Step 2: Compute reference dimensions
        val shoulderWidth = distance(
            bodyLandmarks.leftShoulder.x, bodyLandmarks.leftShoulder.y,
            bodyLandmarks.rightShoulder.x, bodyLandmarks.rightShoulder.y
        )
        val torsoHeight = distance(shoulderCenter.x, shoulderCenter.y, hipCenter.x, hipCenter.y)
        
        // Step 3: Compute offsets (ratios, not pixels)
        // REDUCED by 90% (keeping 10% of previous values)
        val shoulderOut = shoulderWidth * 0.82f  // 10% of 2.4f
        val chestOut = shoulderWidth * 0.75f     // 10% of 2.7f
        val hemOut = shoulderWidth * 0.9f       // 10% of 2.4f
        
        val shoulderDrop = torsoHeight * 0.1f
        val chestDrop = torsoHeight * 0.3f
        val shoulderLift = torsoHeight * 0.15f  // Move p0, p1 upward above s1, s2
        
        // Step 4: Compute fabric silhouette points (p0-p5)
        // p0 and p1: Move UPWARD (subtract from Y) to position above s1, s2
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
        
        // Draw black dots for fabric silhouette
        drawPoint(p0, Color.Black, radius = 14f)
        drawPoint(p1, Color.Black, radius = 14f)
        drawPoint(p2, Color.Black, radius = 14f)
        drawPoint(p3, Color.Black, radius = 14f)
        drawPoint(p4, Color.Black, radius = 14f)
        drawPoint(p5, Color.Black, radius = 14f)
        
        // Draw labels for black dots
        drawLabel(p0, "p0")
        drawLabel(p1, "p1")
        drawLabel(p2, "p2")
        drawLabel(p3, "p3")
        drawLabel(p4, "p4")
        drawLabel(p5, "p5")
        
        // ===================================================================
        // WHITE LINES: Connect fabric silhouette points (optional)
        // ===================================================================
        // Top edge: p0 → p1
        drawLine(color = Color.White, start = p0, end = p1, strokeWidth = 2f)
        
        // Left side: p0 → p2 → p4
        drawLine(color = Color.White, start = p0, end = p2, strokeWidth = 2f)
        drawLine(color = Color.White, start = p2, end = p4, strokeWidth = 2f)
        
        // Right side: p1 → p3 → p5
        drawLine(color = Color.White, start = p1, end = p3, strokeWidth = 2f)
        drawLine(color = Color.White, start = p3, end = p5, strokeWidth = 2f)
    }
}

