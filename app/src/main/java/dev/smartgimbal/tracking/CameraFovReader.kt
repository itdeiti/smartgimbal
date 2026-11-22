package dev.smartgimbal.tracking

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Reads the field of view of the first back or front camera from Camera2 characteristics. */
object CameraFovReader {

    fun read(context: Context, front: Boolean): Fov? = runCatching {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return null
        val c = manager.getCameraCharacteristics(id)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        Fov.fromSensor(size.width, size.height, focal)
    }.getOrNull()
}
