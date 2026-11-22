package dev.smartgimbal.ui

import android.graphics.RectF
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
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
 *
 * Boxes come in the upright analysis frame; CoordinateTransform maps them onto the PreviewView,
 * which takes care of the FILL_CENTER crop and the front camera mirroring.
 */
@Composable
fun FaceOverlay(frame: FaceFrame?, previewView: PreviewView?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        val target = previewView?.outputTransform ?: return@Canvas
        val transform = CoordinateTransform(f.sourceTransform, target)
        f.faces.forEach { face ->
            val isTarget = face == f.target
            drawFace(face, f, transform, if (isTarget) TARGET_COLOR else OTHER_COLOR, if (isTarget) 6f else 3f)
        }
    }
}

private fun DrawScope.drawFace(face: DetectedFace, frame: FaceFrame, transform: CoordinateTransform, color: Color, width: Float) {
    val rect = RectF(
        face.box.left * frame.imageWidth,
        face.box.top * frame.imageHeight,
        face.box.right * frame.imageWidth,
        face.box.bottom * frame.imageHeight,
    )
    transform.mapRect(rect)
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width(), rect.height()),
        cornerRadius = CornerRadius(16f, 16f),
        style = Stroke(width = width),
    )
}
