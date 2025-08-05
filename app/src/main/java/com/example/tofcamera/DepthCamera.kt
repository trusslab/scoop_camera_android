package com.example.tofcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.widget.TextView
import androidx.camera.viewfinder.CameraViewfinder
import androidx.camera.viewfinder.CameraViewfinderExt.requestSurface
import androidx.camera.viewfinder.ViewfinderSurfaceRequest
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import kotlin.experimental.and

class DepthCamera<T>(
  private val context: T,
  private val shouldUseDynamicRanging: () -> Boolean,
  private val setRangeText: (String) -> Unit,
  private val setFPSText: (String) -> Unit,
  private val setStatusText: (String) -> Unit,
  private val callerLayout: CoordinatorLayout,
  private val viewFinder: CameraViewfinder,
  private val recordTimerTextView: TextView,
) : CameraDevice.StateCallback(), ImageReader.OnImageAvailableListener
  where T : Context, T : DepthCameraImageListener {
  var isOpen: Boolean = false
  private var isFirstTimeOpening = true
  var handler: Handler? = null

  private val savedContext = context
  private val currentClassInstance = this

  private val debug_is_capturing_depth = true // for energy consumption

  private val TAG: String = DepthCamera::class.java.simpleName

  private val cameraManager = context.getSystemService(CameraManager::class.java)
  private lateinit var depthImageReader: ImageReader
  private lateinit var mainImageReader: ImageReader
  private lateinit var depthRequestBuilder: CaptureRequest.Builder
  private lateinit var mainRequestBuilder: CaptureRequest.Builder
  private var depthCaptureSession: CameraCaptureSession? = null
  private var mainCaptureSession: CameraCaptureSession? = null
  private lateinit var depthCameraId: String
  private lateinit var mainCameraId: String

  private val ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
  }
  private var depthSensorOrientation = 0
  private var mainSensorOrientation = 0
  private var finalOrientation = 0

  // for preview
  private lateinit var viewFinderSurface: Surface

  // for capturing
  var isCapturing = false
  var isCapturingDepth = false
  var isCapturingRGB = false
  private lateinit var rgbPhotoFile: File
  private lateinit var depthPhotoFile: File

  // for recording
  private lateinit var mainCamera: CameraDevice
  private lateinit var depthCamera: CameraDevice
  private val rgbMediaRecordingSurface = MediaCodec.createPersistentInputSurface()
  var isRecording = false
  private var currentRecordingTime: Long = 0  // in seconds
  private lateinit var recordingTimerJob: Job
  private lateinit var rgbMediaRecorder: MediaRecorder
  private lateinit var depthRecordingFile: File
  private var depthRecordingPreviousFrameTimeStamp: Long = 0  // in milliseconds
  private var depthRecordingElapsedTime: Long = 0  // in milliseconds

  init {
    val depthCameraId = getDepthCameraId()
    val mainCameraId = getMainCameraId()
    var errMsg = ""

    if (depthCameraId == null) {
      Log.e(TAG, "Couldn't find a Depth Camera")
      errMsg += "Couldn't find a Depth Camera\n"
    } else {
      this.depthCameraId = depthCameraId
    }

    if (mainCameraId == null) {
      Log.e(TAG, "Couldn't find a Main Camera")
      errMsg += "Couldn't find a Main Camera\n"
    } else {
      this.mainCameraId = mainCameraId
    }

    if (errMsg.isNotEmpty()) {
      Snackbar.make(callerLayout, errMsg, Snackbar.LENGTH_LONG).show()
    }

  }

  fun open() {
    if (isOpen) {
      return
    }

    Log.i(TAG, "Opening camera session.")

    isOpen = true

    setStatusText("Camera is starting...")

    if (handler == null) {
      Log.e(TAG, "Camera thread handler is null!")
      return
    }

    // set up preview and open cameras
    viewFinder.scaleType = CameraViewfinder.ScaleType.FIT_CENTER
    val builder = ViewfinderSurfaceRequest.Builder(Size(MyApp.mainPreviewWidth, MyApp.mainPreviewHeight))
    builder.setImplementationMode(CameraViewfinder.ImplementationMode.COMPATIBLE) // can't use PERFORMANCE due to it's incompatibility with the depth camera's SurfaceView
    builder.setSensorOrientation(mainSensorOrientation)
    builder.setLensFacing(CameraCharacteristics.LENS_FACING_BACK)
    val viewFinderRequest = builder.build()
    CoroutineScope(Dispatchers.Main).launch {
      viewFinderSurface = viewFinder.requestSurface(viewFinderRequest)
      openCameras()
    }
  }

  // Get all local files' names
  fun getCapturedFiles(): List<String> {
    val files = context.getExternalFilesDir(null)?.listFiles()
    val fileNames = mutableListOf<String>()
    files?.forEach {
      fileNames.add(it.name)
    }
    return fileNames
  }

  // Delete all local files
  fun clearCapturedFiles() {
    val files = context.getExternalFilesDir(null)?.listFiles()
    files?.forEach {
      it.delete()
    }
  }

  private fun openCameras() {

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Camera permission is not granted!")
      Snackbar.make(callerLayout, "Camera permission is not granted!", Snackbar.LENGTH_LONG).show()
      return
    }

    if (debug_is_capturing_depth) {
      cameraManager.openCamera(depthCameraId, this, null)
    }
    cameraManager.openCamera(mainCameraId, this, null)

    Log.i(TAG, "Camera session open.")
  }

  private fun getDepthCameraId(): String? {
    for (depthCameraId in cameraManager.cameraIdList) {
      val chars = cameraManager.getCameraCharacteristics(depthCameraId)

      if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
        continue
      }

      val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue

      if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
        depthSensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Print out the focal length of the depth camera
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        Log.i(TAG, "Depth Camera Focal Lengths: ${focalLengths?.joinToString()}")

        // Print out the intrinsic calibration of the depth camera
        val intrinsicCalibration = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        Log.i(TAG, "Depth Camera Intrinsic Calibration: ${intrinsicCalibration?.joinToString()}")

        return depthCameraId
      }
    }

    return null
  }

  private fun getMainCameraId(): String? {
    for (depthCameraId in cameraManager.cameraIdList) {
      val chars = cameraManager.getCameraCharacteristics(depthCameraId)

      if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
        continue
      }

      MyApp.mainCameraChars = chars
      mainSensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

      return depthCameraId
    }

    return null
  }

  private fun getMediaRecorder(outputFileAddress: String): MediaRecorder {
    val resultMediaRecorder: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(savedContext)
    } else {
      MediaRecorder()
    }

    resultMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
    resultMediaRecorder.setInputSurface(rgbMediaRecordingSurface)
    resultMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    resultMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    resultMediaRecorder.setVideoEncodingBitRate(5000000)
    resultMediaRecorder.setVideoSize(MyApp.mainRecordWidth, MyApp.mainRecordHeight)
    resultMediaRecorder.setOutputFile(outputFileAddress)
    resultMediaRecorder.prepare()

    return resultMediaRecorder
  }

  private fun getMainCameraViewFinderRequestBuilder(templateType: Int): CaptureRequest.Builder {
    val resultRequestBuilder = mainCamera.createCaptureRequest(templateType)
    resultRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, finalOrientation)
    resultRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(MyApp.mainTargetFPS, MyApp.mainTargetFPS))
    resultRequestBuilder.addTarget(viewFinderSurface)
    return resultRequestBuilder
  }

  private fun openMainCameraCaptureSession() {
    // Due to the requirement of using a persistent surface for recording, we need to create a temporary dummy media recorder instance (1/2)
    // Setup (1/2)
    if (isFirstTimeOpening) {
      rgbMediaRecorder = getMediaRecorder(context.getExternalFilesDir(null)?.absolutePath + "/temp.mp4")
    }

    mainRequestBuilder = getMainCameraViewFinderRequestBuilder(CameraDevice.TEMPLATE_PREVIEW)
    mainRequestBuilder.addTarget(rgbMediaRecordingSurface)
    mainRequestBuilder.addTarget(mainImageReader.surface)

    val outputConfigurationList = mutableListOf<OutputConfiguration>()
    outputConfigurationList.add(OutputConfiguration(viewFinderSurface))
    outputConfigurationList.add(OutputConfiguration(rgbMediaRecordingSurface))
    outputConfigurationList.add(OutputConfiguration(mainImageReader.surface))

    val outputConfiguration = SessionConfiguration(
      SessionConfiguration.SESSION_REGULAR,
      outputConfigurationList,
      context.mainExecutor,
      object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
          Log.i(TAG, "Main Capture Session created")
          mainCaptureSession = session
          mainCaptureSession!!.setRepeatingRequest(mainRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
              updateFPS(1)
              super.onCaptureCompleted(session, request, result)
            }
          }, null)

          // Due to the requirement of using a persistent surface for recording, we need to create a temporary dummy media recorder instance (2/2)
          // Cleanup (2/2)
          if (isFirstTimeOpening) {
            rgbMediaRecorder.reset()
            rgbMediaRecorder.release()
            isFirstTimeOpening = false
            File(context.getExternalFilesDir(null)?.absolutePath + "/temp.mp4").delete()
          }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
          Log.e(TAG, "Failed to configure the Main Camera!")
        }
      }
    )

    mainCamera.createCaptureSession(outputConfiguration)
  }

  override fun onOpened(camera: CameraDevice) {
    CoroutineScope(Dispatchers.Default).launch {
      finalOrientation = (ORIENTATIONS.get(savedContext.display?.rotation ?: 0) + mainSensorOrientation + 270) % 360
      if (camera.id == depthCameraId) {
        depthCamera = camera
        Log.i(
          TAG,
          "Depth Camera has been opened with display rotation: ${savedContext.display?.rotation ?: 0} and sensor orientation: $depthSensorOrientation and final orientation: ${
            (ORIENTATIONS.get(savedContext.display?.rotation ?: 0) + depthSensorOrientation + 270) % 360
          }"
        )

        depthImageReader = ImageReader.newInstance(MyApp.depthWidth, MyApp.depthHeight, ImageFormat.DEPTH16, MyApp.MAX_HOLDING_FRAMES)
        depthImageReader.setOnImageAvailableListener(currentClassInstance, handler)

        depthRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        depthRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS.get(savedContext.display?.rotation ?: 0) + depthSensorOrientation + 270) % 360)
        depthRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(MyApp.depthTargetFPS, MyApp.depthTargetFPS))
        depthRequestBuilder.addTarget(depthImageReader.surface)

//        val depthCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
//          override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
//            val cameraIntrinsics = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
//            Log.i(TAG, "Camera Intrinsics: ${cameraIntrinsics!![0]}, ${cameraIntrinsics[1]}, ${cameraIntrinsics[2]}, ${cameraIntrinsics[3]}")
//            Log.i(TAG, "Camera focal length: ${cameraManager.getCameraCharacteristics(depthCameraId).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
//            super.onCaptureCompleted(session, request, result)
//          }
//        }

        camera.createCaptureSession(
          SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(depthImageReader.surface)),
            context.mainExecutor,
            object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(session: CameraCaptureSession) {
                Log.i(TAG, "Capture Session created")
                depthCaptureSession = session

                depthCaptureSession!!.setRepeatingRequest(depthRequestBuilder.build(), null, null)
//                depthCaptureSession!!.setRepeatingRequest(depthRequestBuilder.build(), depthCaptureCallback, null)
              }

              override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure the Depth Camera!")
              }
            })
        )
      } else if (camera.id == mainCameraId) {
        mainCamera = camera
        Log.i(TAG, "Main Camera has been opened with display rotation: ${savedContext.display?.rotation ?: 0} and sensor orientation: $mainSensorOrientation and final orientation: $finalOrientation")

        val chars = cameraManager.getCameraCharacteristics(mainCameraId)
        Log.i(
          TAG,
          "Available main camera output sizes: ${chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG).joinToString { "${it.width}x${it.height}" }}"
        )
        mainImageReader = ImageReader.newInstance(MyApp.mainPreviewWidth, MyApp.mainPreviewHeight, ImageFormat.JPEG, MyApp.MAX_HOLDING_FRAMES)
        mainImageReader.setOnImageAvailableListener(currentClassInstance, handler)

        openMainCameraCaptureSession()

      }
    }
  }

  fun startCapturing(captureFileGeneralName: String): Int {

    Log.d(TAG, "Starting photo capturing at: ${System.currentTimeMillis()}")

    if (isCapturing) {
      Log.e(TAG, "Already capturing!")
      return 1
    }
    if (isRecording) {
      Log.e(TAG, "Already recording!")
      return 1
    }
    if (!isOpen) {
      Log.e(TAG, "Camera session is not open!")
      return 1
    }

    CoroutineScope(Dispatchers.IO).launch {
      delay(MyApp.WAIT_TIME_FOR_CAPTURING)

      // Init depth capturing
      if (debug_is_capturing_depth) {
        depthPhotoFile = File(context.getExternalFilesDir(null)?.absolutePath + "/${captureFileGeneralName}_photo_depth.adep")
        val depthFileHeaderByteArray = ByteArray(1 + Int.SIZE_BYTES * 4)
        depthFileHeaderByteArray[0] = 0 // platform id: 0 for Android, 1 for iOS
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MyApp.depthRecordingWidth).array().copyInto(depthFileHeaderByteArray, 1)
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MyApp.depthRecordingHeight).array().copyInto(depthFileHeaderByteArray, 1 + Int.SIZE_BYTES)
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MyApp.depthPixelSize).array().copyInto(depthFileHeaderByteArray, 1 + Int.SIZE_BYTES * 2)
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(0).array().copyInto(depthFileHeaderByteArray, 1 + Int.SIZE_BYTES * 3)  // 0 means dynamic fps (each frame has an additional timestamp)
        depthPhotoFile.writeBytes(depthFileHeaderByteArray)
      }

      // Init RGB capturing
      rgbPhotoFile = File(context.getExternalFilesDir(null)?.absolutePath + "/${captureFileGeneralName}_photo_rgb.jpg")

      // Mark the capturing as started
      isCapturing = true
      if (isCapturingDepth) {
        isCapturingDepth = true
      }
      isCapturingRGB = true
    }

    return 0
  }

  fun startRecording(recordFileGeneralName: String): Int {
    if (isCapturing) {
      Log.e(TAG, "Already capturing!")
      return 1
    }
    if (isRecording) {
      Log.e(TAG, "Already recording!")
      return 1
    }
    if (!isOpen) {
      Log.e(TAG, "Camera session is not open!")
      return 1
    }

    CoroutineScope(Dispatchers.IO).launch {
      // Init depth capturing
      if (debug_is_capturing_depth) {
        depthRecordingFile = File(context.getExternalFilesDir(null)?.absolutePath + "/${recordFileGeneralName}_depth.adep")
        val depthFileHeaderByteArray = ByteArray(1 + Int.SIZE_BYTES * 4)
        depthFileHeaderByteArray[0] = 0 // platform id: 0 for Android, 1 for iOS
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MyApp.depthRecordingWidth).array().copyInto(depthFileHeaderByteArray, 1)
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MyApp.depthRecordingHeight).array().copyInto(depthFileHeaderByteArray, 1 + Int.SIZE_BYTES)
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MyApp.depthPixelSize).array().copyInto(depthFileHeaderByteArray, 1 + Int.SIZE_BYTES * 2)
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(0).array().copyInto(depthFileHeaderByteArray, 1 + Int.SIZE_BYTES * 3)  // 0 means dynamic fps (each frame has an additional timestamp)
        depthRecordingFile.writeBytes(depthFileHeaderByteArray)
      }

      // Start RGB recording
      rgbMediaRecorder = getMediaRecorder(context.getExternalFilesDir(null)?.absolutePath + "/${recordFileGeneralName}_rgb.mp4")
      rgbMediaRecorder.start()

      // Mark the recording as started
      depthRecordingPreviousFrameTimeStamp = System.currentTimeMillis()
      isRecording = true
      context.mainExecutor.execute { recordTimerTextView.setBackgroundColor(Color.RED) }

      // Start a timer to update the recording time text view (GUI)
      recordingTimerJob = Job()
      CoroutineScope(Dispatchers.Default + recordingTimerJob).launch {
        while (isRecording) {
          val hours = currentRecordingTime / 3600
          val minutes = (currentRecordingTime % 3600) / 60
          val seconds = currentRecordingTime % 60
          val recordTimerString = String.format("%02d:%02d:%02d", hours, minutes, seconds)
          context.mainExecutor.execute { recordTimerTextView.text = recordTimerString }
          delay(1000)
          currentRecordingTime += 1
        }
      }
      Log.i(TAG, "Recording started.")
    }

    return 0
  }

  fun stopRecording() {
    if (!isRecording) {
      return
    }

    CoroutineScope(Dispatchers.Default).launch {
      rgbMediaRecorder.stop()
      rgbMediaRecorder.reset()
      rgbMediaRecorder.release()

      isRecording = false
      recordingTimerJob.cancel()
      currentRecordingTime = 0
      recordTimerTextView.setBackgroundColor(Color.TRANSPARENT)
      Log.i(TAG, "Recording stopped.")
    }
  }

  fun close(forceClose: Boolean = false) {
    if (!isOpen) {
      return
    }
    if (!forceClose && isRecording) {
      Log.d(TAG, "Cannot close camera session while recording!")
      return
    }

    Log.i(TAG, "Closing capture session.")

    if (isRecording) {
      stopRecording()
    }

    depthCaptureSession?.abortCaptures()
    depthCaptureSession?.stopRepeating()
    depthCaptureSession?.close()

    mainCaptureSession?.abortCaptures()
    mainCaptureSession?.stopRepeating()
    mainCaptureSession?.close()
    mainCaptureSession = null

    isOpen = false

    Log.i(TAG, "Capture session closed.")
  }

  override fun onDisconnected(camera: CameraDevice) {
    Log.i(TAG, "Camera disconnected!")
  }

  override fun onError(camera: CameraDevice, error: Int) {
    Log.e(TAG, "Camera error: $error!")
  }

  val FPS_MEASURED_INTERVAL = 1000
  var lastDepthTime = System.currentTimeMillis()
  var lastMainTime = System.currentTimeMillis()
  var depthFPSCounter = 0
  var mainFPSCounter = 0
  var latestDepthFPS = 0
  var latestMainFPS = 0

  private val RANGE_MIN = 0.toShort()
  private val RANGE_MAX = 5000.toShort()

  private fun normalizeRange(range: Short, min: Short, max: Short): Float {
    var normalized = range.toFloat() - min
    // Clamp to min/max
    normalized = Math.max(min.toFloat(), normalized)
    normalized = Math.min(max.toFloat(), normalized)
    // Normalize to 0 to 240f
    normalized -= min
    normalized = normalized / (max - min).toFloat() * 240f
    return normalized
  }

  private fun rotateImage(image: Bitmap, rotation: Int): Bitmap {
    val matrix = android.graphics.Matrix()
    matrix.postRotate(rotation.toFloat())
    return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
  }

  private fun updateFPS(type: Int) {
//    type: 0 -> depth, 1 -> main
    val currentTime = System.currentTimeMillis()
    if (type == 0)
    {
      val timeDiff = currentTime - lastDepthTime
      if (timeDiff > FPS_MEASURED_INTERVAL) {
        latestDepthFPS = (depthFPSCounter / (timeDiff / 1000)).toInt()
        lastDepthTime = currentTime
        depthFPSCounter = 0
      } else {
        depthFPSCounter++
      }
    } else if (type == 1)
    {
      val timeDiff = currentTime - lastMainTime
      if (timeDiff > FPS_MEASURED_INTERVAL) {
        latestMainFPS = (mainFPSCounter / (timeDiff / 1000)).toInt()
        lastMainTime = currentTime
        mainFPSCounter = 0
      } else {
        mainFPSCounter++
      }
    }
    context.mainExecutor.execute { setFPSText("Depth FPS: $latestDepthFPS, Main FPS: $latestMainFPS") }
  }

  private fun cropByteArray(src: ByteArray, width: Int, height: Int, cropRect: Rect, ): ByteArray {
    val x = cropRect.left * 2 / 2
    val y = cropRect.top * 2 / 2
    val w = cropRect.width() * 2 / 2
    val h = cropRect.height() * 2 / 2
    val yUnit = w * h
    val uv = yUnit / 2
    val nData = ByteArray(yUnit + uv)
    val uvIndexDst = w * h - y / 2 * w
    val uvIndexSrc = width * height + x
    var srcPos0 = y * width
    var destPos0 = 0
    var uvSrcPos0 = uvIndexSrc
    var uvDestPos0 = uvIndexDst
    for (i in y until y + h) {
      System.arraycopy(src, srcPos0 + x, nData, destPos0, w) //y memory block copy
      srcPos0 += width
      destPos0 += w
      if (i and 1 == 0) {
        System.arraycopy(src, uvSrcPos0, nData, uvDestPos0, w) //uv memory block copy
        uvSrcPos0 += width
        uvDestPos0 += w
      }
    }
    return nData
  }

  override fun onImageAvailable(reader: ImageReader?) {
    context.mainExecutor.execute { setStatusText("") }

    // TODO: Program may crash here if the reader is full (consider doing a try-catch block here)
    val image = reader!!.acquireLatestImage() ?: return

    // Refresh the timer if it's a depth image and we're recording
    if (isRecording && image.format == ImageFormat.DEPTH16) {
      depthRecordingElapsedTime = System.currentTimeMillis() - depthRecordingPreviousFrameTimeStamp
      depthRecordingPreviousFrameTimeStamp = System.currentTimeMillis()
    }

    // Process the image (could be either depth or RGB image)
    CoroutineScope(Dispatchers.Main).launch {
      if (image.format == ImageFormat.DEPTH16) {

        updateFPS(0)
//        Log.i(TAG, "Pog we got depth image data: ${image.width}x${image.height} px")

        if (!isCapturingDepth && !isRecording && !MyApp.isPreviewing) {
          image.close()
          return@launch
        }

        val depthBuffer = image.planes[0].buffer.asShortBuffer()
        val width = image.width
        val height = image.height

        val widthOffset = 0
        val heightOffset = 5

        // For visualization
        val depthRanges = ShortArray(MyApp.depthScaledWidth * MyApp.depthScaledHeight)
        var minRange: Short = Short.MAX_VALUE
        var maxRange: Short = Short.MIN_VALUE

        // For recording
        val recordDepthByteArray = ByteArray(MyApp.depthRecordingWidth * MyApp.depthRecordingHeight * 2)

        if ((savedContext.display?.rotation ?: 0) == 0)
        {
          for (y in 0 until MyApp.depthScaledHeight) {
            for (x in 0 until MyApp.depthScaledWidth) {
              val index = MyApp.depthScaledHeight * x + y

              val rawSample: Short = depthBuffer.get(width * (height - 1 - (y + heightOffset)) + (x + widthOffset))

              recordDepthByteArray[index * 2] = (rawSample.toInt() and 0xFF).toByte()
              recordDepthByteArray[index * 2 + 1] = (rawSample.toInt() shr 8).toByte()

              // From: https://developer.android.com/reference/android/graphics/ImageFormat#DEPTH16
              val depthRange = rawSample and 0x1FFF
              val depthConfidence = ((rawSample.toInt() shr 13) and 0x7).toShort()
              val confidencePercentage = if (depthConfidence.toInt() == 0) 1.0f else (depthConfidence - 1) / 7.0f

              @Suppress("ConvertTwoComparisonsToRangeCheck")
              if (depthRange < minRange && depthRange > 0) {
                minRange = depthRange
              } else if (depthRange > maxRange) {
                maxRange = depthRange
              }

              if (confidencePercentage > MyApp.CONFIDENCE_THRESHOLD) {
                depthRanges[index] = depthRange
              } else {
                depthRanges[index] = Short.MAX_VALUE
              }
            }
          }
        } else if ((savedContext.display?.rotation ?: 0) == 1)
        {
          for (y in 0 until MyApp.depthScaledHeight) {
            for (x in 0 until MyApp.depthScaledWidth) {
              val index = MyApp.depthScaledWidth * y + x

              val rawSample: Short = depthBuffer.get(width * (y + heightOffset) + (x + widthOffset))

              recordDepthByteArray[index * 2] = (rawSample.toInt() and 0xFF).toByte()
              recordDepthByteArray[index * 2 + 1] = (rawSample.toInt() shr 8).toByte()

              // From: https://developer.android.com/reference/android/graphics/ImageFormat#DEPTH16
              val depthRange = rawSample and 0x1FFF
              val depthConfidence = ((rawSample.toInt() shr 13) and 0x7).toShort()
              val confidencePercentage = if (depthConfidence.toInt() == 0) 1.0f else (depthConfidence - 1) / 7.0f

              @Suppress("ConvertTwoComparisonsToRangeCheck")
              if (depthRange < minRange && depthRange > 0) {
                minRange = depthRange
              } else if (depthRange > maxRange) {
                maxRange = depthRange
              }

              if (confidencePercentage > MyApp.CONFIDENCE_THRESHOLD) {
                depthRanges[index] = depthRange
              } else {
                depthRanges[index] = Short.MAX_VALUE
              }
            }
          }
        }

        // Start a worker thread to store the depth data if we're capturing
        if (isCapturingDepth) {
          CoroutineScope(Dispatchers.IO).launch {
            depthPhotoFile.appendBytes(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(0).array())
            depthPhotoFile.appendBytes(recordDepthByteArray)

            isCapturingDepth = false
            if (!isCapturingRGB) {
              isCapturing = false
              Log.i(TAG, "Capturing stopped.")
            }
          }
        }

//         Start a worker thread to store the depth data if we're recording
        if (isRecording) {
//          Log.d(TAG, "Recording depth data with size: ${tempDepthBufferArray.size} timestamp: ${depthRecordingElapsedTime.toInt()} (Size of Int: ${Int.SIZE_BYTES})")

          CoroutineScope(Dispatchers.IO).launch {
            depthRecordingFile.appendBytes(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(depthRecordingElapsedTime.toInt()).array())
            depthRecordingFile.appendBytes(recordDepthByteArray)
          }
        }

        val pixels = IntArray(MyApp.depthScaledWidth * MyApp.depthScaledHeight)

        for ((i, range) in depthRanges.withIndex()) {
          val normalizedRange = if (shouldUseDynamicRanging()) {
            normalizeRange(range, minRange, maxRange)
          } else {
            normalizeRange(range, RANGE_MIN, RANGE_MAX)
          }

          val color = ColorUtils.HSLToColor(floatArrayOf(240f - normalizedRange, 1.0f, 0.5f))
          pixels[i] = Color.argb((255f * (1 - normalizedRange / 240f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
//          pixels[i] = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))

        }

        val bitmap:Bitmap
        when ((savedContext.display?.rotation ?: 0))
        {
          0 -> {
            bitmap = Bitmap.createBitmap(MyApp.depthScaledHeight, MyApp.depthScaledWidth, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, MyApp.depthScaledHeight, 0, 0, MyApp.depthScaledHeight, MyApp.depthScaledWidth)
          }
          1 -> {
            bitmap = Bitmap.createBitmap(MyApp.depthScaledWidth, MyApp.depthScaledHeight, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, MyApp.depthScaledWidth, 0, 0, MyApp.depthScaledWidth, MyApp.depthScaledHeight)
          }
          else -> {
            bitmap = Bitmap.createBitmap(MyApp.depthScaledHeight, MyApp.depthScaledWidth, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, MyApp.depthScaledHeight, 0, 0, MyApp.depthScaledHeight, MyApp.depthScaledWidth)
          }
        }

        context.mainExecutor.execute { setRangeText("Range: $minRange mm to $maxRange mm") }

        context.onNewImage(bitmap)

        bitmap.recycle()
        image.close()
      }
      else if (image.format == ImageFormat.JPEG)
      {
        // Copy the image data to a byte array
        val rgbByteArray = ByteArray(image.planes[0].buffer.capacity())
        image.planes[0].buffer.get(rgbByteArray)
        image.close()

        // Start a worker thread to store the RGB data if we're capturing
        if (isCapturing) {
          CoroutineScope(Dispatchers.IO).launch {
            rgbPhotoFile.writeBytes(rgbByteArray)

            isCapturingRGB = false
            if (!isCapturingDepth) {
              isCapturing = false
              Log.i(TAG, "Capturing stopped.")
            }
          }

          // Display the image
          val bitmap = BitmapFactory.decodeByteArray(rgbByteArray, 0, rgbByteArray.size)
          val newBitmap = rotateImage(bitmap, finalOrientation)
          context.mainExecutor.execute { context.onNewImage(newBitmap) }
        }
      }
    }
  }
}