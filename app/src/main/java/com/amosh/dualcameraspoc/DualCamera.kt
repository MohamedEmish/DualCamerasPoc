package com.amosh.dualcameraspoc

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.AsyncTask
import android.os.Handler
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import java.util.concurrent.Executor

/**
 * Helper class used to encapsulate a logical camera and two underlying
 * physical cameras
 */
data class DualCamera(val logicalId: String, val physicalId1: String, val physicalId2: String)

@SuppressLint("NewApi")
fun findDualCameras(manager: CameraManager, facing: Int? = null): List<DualCamera> {
    val dualCameras: MutableList<DualCamera> = mutableListOf()

    // Iterate over all the available camera characteristics
    manager.cameraIdList.map {
        Pair(manager.getCameraCharacteristics(it), it)
    }.filter {
        // Filter by cameras facing the requested direction
        facing == null || it.first.get(CameraCharacteristics.LENS_FACING) == facing
    }.filter {
        // Filter by logical cameras
        it.first.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }.forEach {
        // All possible pairs from the list of physical cameras are valid results
        // NOTE: There could be N physical cameras as part of a logical camera grouping
        val physicalCameras = it.first.physicalCameraIds.toTypedArray()
        for (idx1 in physicalCameras.indices) {
            for (idx2 in (idx1 + 1) until physicalCameras.size) {
                dualCameras.add(DualCamera(
                    it.second, physicalCameras[idx1], physicalCameras[idx2]))
            }
        }
    }

    return dualCameras
}

typealias DualCameraOutputs =
    Triple<MutableList<Surface>?, MutableList<Surface>?, MutableList<Surface>?>


fun findShortLongCameraPair(manager: CameraManager, facing: Int? = null): DualCamera? {

    return findDualCameras(manager, facing).map {
        val characteristics1 = manager.getCameraCharacteristics(it.physicalId1)
        val characteristics2 = manager.getCameraCharacteristics(it.physicalId2)

        // Query the focal lengths advertised by each physical camera
        val focalLengths1 = characteristics1.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0F)
        val focalLengths2 = characteristics2.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0F)

        // Compute the largest difference between min and max focal lengths between cameras
        val focalLengthsDiff1 = Collections.max(focalLengths2.toList()) - Collections.min(focalLengths1.toList())
        val focalLengthsDiff2 = Collections.max(focalLengths1.toList()) - Collections.min(focalLengths2.toList())

        // Return the pair of camera IDs and the difference between min and max focal lengths
        if (focalLengthsDiff1 < focalLengthsDiff2) {
            Pair(DualCamera(it.logicalId, it.physicalId1, it.physicalId2), focalLengthsDiff1)
        } else {
            Pair(DualCamera(it.logicalId, it.physicalId2, it.physicalId1), focalLengthsDiff2)
        }

        // Return only the pair with the largest difference, or null if no pairs are found
    }.maxByOrNull { it.second }?.first
}

