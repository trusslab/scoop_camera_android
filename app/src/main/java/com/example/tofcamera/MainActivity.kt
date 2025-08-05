package com.example.tofcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.camera.viewfinder.CameraViewfinder
import androidx.camera.viewfinder.CameraViewfinderExt.requestSurface
import androidx.camera.viewfinder.ViewfinderSurfaceRequest
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.lang.reflect.Modifier
import kotlin.math.round

/*
  TODO:
    - Pointer
    - Frame averaging
    - Try using NDK for image processing
    - Edge detection
    - Switch between 480p and 240p
*/

//class MainActivity : AppCompatActivity(), DepthCameraImageListener, IdleListener {
  class MainActivity : AppCompatActivity(), DepthCameraImageListener {
  private val TAG = "camera-tof";

  private val context = this

  private lateinit var mainLayout: CoordinatorLayout
  private lateinit var cameraViewFinder: CameraViewfinder
  private lateinit var canvasSurfaceView: CanvasSurfaceView
  private lateinit var rgbCapturePreviewImageView: ImageView
  private lateinit var statusTextView: TextView
  private lateinit var captureFileNameEditText: EditText
  private lateinit var captureButton: FloatingActionButton
  private lateinit var recordButton: FloatingActionButton
  private lateinit var recordTimerTextView: TextView
  private lateinit var clearCacheButton: Button
  private lateinit var previewCheckBox: CheckBox

  private lateinit var cameraThread: HandlerThread
  private lateinit var camera: DepthCamera<MainActivity>
//  private lateinit var idleMonitor: AccelerometerIdleMonitor<MainActivity>

  // Matrix that scales the recorded frame to the size of the surfaceView.
  private lateinit var bitmapMatrix: Matrix
//  private val bitmapMatrix: Matrix by lazy {
//    val matrix = Matrix()
//    val bufferWidth = MyApp.depthHeight
//    val bufferHeight = MyApp.depthWidth
//    val bufferRect = RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
//    val viewRect = RectF(0f, 0f, surfaceView.width.toFloat(), surfaceView.height.toFloat())
//
//    matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)
//
//    matrix
//  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(findViewById(R.id.toolbar))

    mainLayout = findViewById(R.id.main_coordinator_layout)
    cameraViewFinder = findViewById(R.id.camera_view_finder)
    canvasSurfaceView = findViewById(R.id.canvas_surface_view)
    rgbCapturePreviewImageView = findViewById(R.id.rgb_capture_preview_imageview)
    captureFileNameEditText = findViewById(R.id.captureEditText)
    captureButton = findViewById(R.id.captureButton)
    recordButton = findViewById(R.id.recordButton)
    recordTimerTextView = findViewById(R.id.captureTimerTextView)
    clearCacheButton = findViewById(R.id.clearCacheButton)

//    idleMonitor = AccelerometerIdleMonitor(this)

    printCameraInfo()

    val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

    if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
    }

    val dynamicRangeCheckbox = findViewById<CheckBox>(R.id.dynamicRangeCheckbox)
    val rangeTextView = findViewById<TextView>(R.id.rangeTextView)
    val fpsTextView = findViewById<TextView>(R.id.fpsTextView)
    statusTextView = findViewById(R.id.cameraStatusTextView)
    previewCheckBox = findViewById(R.id.preview_enabler)

    MyApp.isPreviewing = previewCheckBox.isChecked
    previewCheckBox.setOnClickListener { view ->
      if (previewCheckBox.isChecked) {
        MyApp.isPreviewing = true
        cameraViewFinder.visibility = SurfaceView.VISIBLE
        canvasSurfaceView.visibility = GLSurfaceView.VISIBLE
      } else {
        MyApp.isPreviewing = false
        cameraViewFinder.visibility = SurfaceView.GONE
        canvasSurfaceView.visibility = GLSurfaceView.GONE
      }
    }

//    canvasSurfaceView.setZOrderOnTop(true)
//    canvasSurfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
    canvasSurfaceView.isOpaque = false
    camera = DepthCamera(context, dynamicRangeCheckbox::isChecked, rangeTextView::setText, fpsTextView::setText, statusTextView::setText, mainLayout, cameraViewFinder, recordTimerTextView)
    startCameraThread()
    camera.open()

    captureButton.setOnClickListener {
      camera.startCapturing(captureFileNameEditText.text.toString())
    }

    rgbCapturePreviewImageView.setOnClickListener {
      stopIdle()
      rgbCapturePreviewImageView.visibility = ImageView.GONE
    }

    recordButton.setOnClickListener {
      if (camera.isRecording) {
        camera.stopRecording()
        recordButton.setImageResource(R.drawable.record_start_button)
      } else {
        if (camera.startRecording(captureFileNameEditText.text.toString()) != 0)
        {
          recordButton.setImageResource(R.drawable.record_end_button)
        }
      }
    }

    clearCacheButton.setOnClickListener {
      val capturedFileNames = camera.getCapturedFiles()
      val dialogMsg = StringBuilder()
      dialogMsg.append("Number of files: ")
      dialogMsg.append(capturedFileNames.size)
      dialogMsg.append("\n")
      for (fileName in capturedFileNames) {
        dialogMsg.append(fileName)
        dialogMsg.append("\n")
      }
      val dialogBuilder = AlertDialog.Builder(this)
      dialogBuilder.setTitle("Captured History")
      dialogBuilder.setMessage(dialogMsg.toString())
      dialogBuilder.setCancelable(true)
      dialogBuilder.setPositiveButton("Clear") { _, _ ->
        camera.clearCapturedFiles()
      }
      dialogBuilder.setNegativeButton("Cancel") { dialog, _ ->
        dialog.cancel()
      }
      val dialog = dialogBuilder.create()
      dialog.show()
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED)
    }

//    idleMonitor.start()
  }

  private fun startCameraThread() {
    cameraThread = HandlerThread("Camera")
    cameraThread.start()

    camera.handler = Handler(cameraThread.looper)
  }

  private fun <T, TValue> getFieldNameByValue(klass: Class<T>, value: TValue, modifiers: Int = 0): String? {
    for (field in klass.declaredFields) {
      if ((field.modifiers and modifiers) == modifiers) {
        if (field.get(this) == value) {
          return field.name
        }
      }
    }

    return null
  }

  private fun printCameraInfo() {
    val cameraManager = getSystemService(CameraManager::class.java)

    for (cameraId in cameraManager.cameraIdList) {
      val chars = cameraManager.getCameraCharacteristics(cameraId)
      val builder = StringBuilder()

      builder.appendLine()
      builder.appendLine("Camera $cameraId:")

      val facing = chars.get(CameraCharacteristics.LENS_FACING)
      builder.appendLine(
        "\tFacing: ${
          when (facing) {
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            CameraMetadata.LENS_FACING_BACK -> "Back"
            CameraMetadata.LENS_FACING_EXTERNAL -> "External"
            else -> throw IllegalArgumentException("Unknown lens facing: $facing")
          }
        }"
      )

      builder.appendLine("\tPhysical size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} mm")

      val activePixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as Rect;
      builder.appendLine("\tActive pixel array size: ${activePixelArray.width()}x${activePixelArray.height()}")

      builder.appendLine("\tTotal pixel array size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")

      val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
      builder.appendLine(
        "\tCapabilities: ${
          capabilities!!.joinToString(", ") { capability ->
            val prefix = "REQUEST_AVAILABLE_CAPABILITIES_"

            CameraMetadata::class.java.declaredFields.find {
              it.name.startsWith(prefix) && it.get(this) == capability
            }!!.name.removePrefix(prefix)
          }
        }"
      )

      val configurationMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
      builder.appendLine(
        "\tFormats: ${
          configurationMap.outputFormats.joinToString(", ") { getFieldNameByValue(ImageFormat::class.java, it, Modifier.PUBLIC or Modifier.STATIC)!! }
        }"
      )

      val getResolutionsForFormat = { format: Int ->
        val resolutions = configurationMap.getOutputSizes(format)

        "\tResolutions (${getFieldNameByValue(ImageFormat::class.java, format, Modifier.PUBLIC or Modifier.STATIC)}): ${
          resolutions.joinToString(", ") { "$it (${round(1 / (configurationMap.getOutputMinFrameDuration(format, it) / 1e9))} FPS)" }
        }"
      }

      if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
        builder.appendLine(getResolutionsForFormat(ImageFormat.DEPTH16))
      } else {
        builder.appendLine(getResolutionsForFormat(ImageFormat.JPEG))
        builder.appendLine(getResolutionsForFormat(ImageFormat.YUV_420_888))
      }

      Log.i(TAG, builder.toString())
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.action_settings -> true
      else -> super.onOptionsItemSelected(item)
    }
  }

  override fun onResume() {
    super.onResume()

    stopIdle()
//    idleMonitor.start()
  }

  override fun onStop() {
    super.onStop()

    startIdle(true)
//    idleMonitor.stop()
  }

  private fun stopIdle() {
    // quit if the camera is not initialized
    if (!::camera.isInitialized) {
      return
    }

    if (!cameraThread.isAlive) {
      startCameraThread()
    }

    if (!camera.isOpen) {
      camera.open()
    }

    Log.i(TAG, "Stopped idling.")
  }

  private fun startIdle(forceStop: Boolean = false) {
    // quit if the camera is not initialized
    if (!::camera.isInitialized) {
      return
    }

    // quit if the camera is recording
    if (!forceStop && camera.isRecording) {
      return
    }

    if (camera.isOpen) {
      camera.close(forceStop)
    }

    if (cameraThread.isAlive) {
      cameraThread.quitSafely()
    }

    //socket.close()

    statusTextView.text = "Camera paused due to inactivity..."

//    val canvas = cameraViewFiner.holder.lockCanvas()
//    if (canvas == null) {
//      Log.e(TAG, "Failed to lock canvas.")
//      return
//    }
//    canvas.drawARGB(255, 0, 0, 0)
//    cameraViewFiner.holder.unlockCanvasAndPost(canvas)

    Log.i(TAG, "Started idling.")
  }

  override fun onNewImage(bitmap: Bitmap) {
    // Judge by the size of the bitmap to determine whether it is a depth image or a color image
    if (bitmap.width == MyApp.mainWidth && bitmap.height == MyApp.mainHeight) {
      rgbCapturePreviewImageView.setImageBitmap(bitmap)
      rgbCapturePreviewImageView.visibility = ImageView.VISIBLE
      Log.d(TAG, "Ending photo capturing at: ${System.currentTimeMillis()}")
      startIdle()
      return
    }

    val canvas = canvasSurfaceView.lockCanvas()
    if (canvas == null) {
      Log.e(TAG, "Failed to lock canvas.")
      return
    }

//    Log.i(TAG, "Bitmap: ${bitmap.width}x${bitmap.height}, Surface: ${canvasSurfaceView.width}x${canvasSurfaceView.height}")
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    canvas.drawBitmap(bitmap, canvasSurfaceView.getBitmapMatrix(), null) // Adjust alpha for desired transparency

    canvasSurfaceView.unlockCanvasAndPost(canvas)
  }

//  override fun onIdle() {
//    startIdle()
//  }
//
//  override fun onIdleStop() {
//    stopIdle()
//  }
}