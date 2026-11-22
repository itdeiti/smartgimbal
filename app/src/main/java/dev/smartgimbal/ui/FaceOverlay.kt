package dev.smartgimbal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.smartgimbal.tracking.DetectedFace
import dev.smartgimbal.tracking.FaceFrame

private val TARGET_COLOR = Color(0xFF4CAF50)
private val OTHER_COLOR = Color.White.copy(alpha = 0.7f)

/**
 * Draws detected faces on top of the preview: the target in green, others in white.
 * Faces are already in display coordinates (normalised over the preview), so no transform needed.
 */
@Composable
fun FaceOverlay(frame: FaceFrame?, showCenter: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (showCenter) drawCenterCross()
        val f = frame ?: return@Canvas
        f.faces.forEach { face ->
            val isTarget = face == f.target
            drawFace(face, if (isTarget) TARGET_COLOR else OTHER_COLOR, if (isTarget) 6f else 3f)
        }
    }
}

/** Image centre, the point the tracker steers the face to. */
private fun DrawScope.drawCenterCross() {
    val c = center
    val arm = 24f
    drawLine(TARGET_COLOR, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 3f)
    drawLine(TARGET_COLOR, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 3f)
}

private fun DrawScope.drawFace(face: DetectedFace, color: Color, width: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(face.box.left * size.width, face.box.top * size.height),
        size = Size(face.box.width * size.width, face.box.height * size.height),
        cornerRadius = CornerRadius(16f, 16f),
        style = Stroke(width = width),
    )
}
