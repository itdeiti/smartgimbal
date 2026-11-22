package dev.smartgimbal.tracking

import android.annotation.SuppressLint
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** ML Kit face detection with the bundled model; runs on the CPU and provides tracking IDs. */
class MlKitFaceDetector : FaceDetector {

    override val name = "ML Kit"

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build(),
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun detect(image: ImageProxy): List<DetectedFace> {
        val media = image.image ?: return emptyList()
        val rotation = image.imageInfo.rotationDegrees
        // Blocking is fine: this runs on the dedicated analysis thread, never on the main thread.
        val faces = Tasks.await(detector.process(InputImage.fromMediaImage(media, rotation)))

        // ML Kit reports boxes in the rotated (upright) frame.
        val uprightWidth = if (rotation % 180 == 0) image.width else image.height
        val uprightHeight = if (rotation % 180 == 0) image.height else image.width
        return faces.map { face ->
            val b = face.boundingBox
            DetectedFace(
                trackingId = face.trackingId,
                box = NormBox(
                    left = b.left.toFloat() / uprightWidth,
                    top = b.top.toFloat() / uprightHeight,
                    right = b.right.toFloat() / uprightWidth,
                    bottom = b.bottom.toFloat() / uprightHeight,
                ),
            )
        }
    }

    override fun close() = detector.close()

    private companion object {
        /** Face width relative to image width; 0.1 still catches a person a few metres away. */
        const val MIN_FACE_SIZE = 0.1f
    }
}
