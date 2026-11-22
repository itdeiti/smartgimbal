package dev.smartgimbal.tracking

import android.graphics.Bitmap
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

    override fun detect(bitmap: Bitmap): List<DetectedFace> {
        // Blocking is fine: this runs on the dedicated analysis thread, never on the main thread.
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return faces.map { face ->
            val b = face.boundingBox
            DetectedFace(
                trackingId = face.trackingId,
                box = NormBox(b.left / w, b.top / h, b.right / w, b.bottom / h),
            )
        }
    }

    override fun close() = detector.close()

    private companion object {
        /** Face width relative to image width; 0.1 still catches a person a few metres away. */
        const val MIN_FACE_SIZE = 0.1f
    }
}
