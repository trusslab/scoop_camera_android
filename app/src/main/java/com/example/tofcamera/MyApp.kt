package com.example.tofcamera

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics

class MyApp: Application() {

    companion object {

      fun computeRelativeRotation(
        characteristics: CameraCharacteristics,
        surfaceRotationDegrees: Int
      ): Int {
        val sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        // Reverse device orientation for back-facing cameras.
        val sign = if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1

        // Calculate desired orientation relative to camera orientation to make
        // the image upright relative to the device orientation.
        return (sensorOrientationDegrees - surfaceRotationDegrees * sign + 360) % 360
      }

//      Depth related
      const val CONFIDENCE_THRESHOLD = 0.00f  // Anything with confidence lower than this value is considered as invalid
      const val MAX_HOLDING_FRAMES = 50  // Maximum number of frames to hold the last valid depth frame
      var depthWidth = 320
      var depthHeight = 240
      var depthScaledWidth = 310
//      var depthScaledHeight = 240
      var depthScaledHeight = 205
      var depthRecordingWidth = depthScaledWidth
      var depthRecordingHeight = depthScaledHeight
      var depthTargetFPS = 20
      var depthPixelSize = 2  // in bytes

//      Normal camera related
//      var mainWidth = 4032
//      var mainHeight = 3024
//      var mainPreviewWidth = 4032
      var mainPreviewWidth = 3840
//      var mainPreviewHeight = 1816
//      var mainPreviewHeight = 2268
      var mainPreviewHeight = 2160
      var mainRecordWidth = 3840
      var mainRecordHeight = 2160
      var mainWidth = 3840
      var mainHeight = 2160
      var mainTargetFPS = 30

      // capturing related
      const val WAIT_TIME_FOR_CAPTURING = 4000L

      lateinit var mainCameraChars: CameraCharacteristics

      var isPreviewing = true
    }

    override fun onCreate() {
        super.onCreate()
    }
}