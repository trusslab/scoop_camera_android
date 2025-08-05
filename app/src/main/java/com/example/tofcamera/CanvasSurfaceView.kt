package com.example.tofcamera

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView

class CanvasSurfaceView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs) {

  private val TAG = "CanvasSurfaceView"
  private lateinit var bitmapMatrix: Matrix

  fun getBitmapMatrix(): Matrix {
    return bitmapMatrix
  }

  private fun updateBitmapMatrix(measuredWidth: Int, measuredHeight: Int) {
      if (this.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
      {
        Log.i(TAG, "Buffer: ${MyApp.depthScaledWidth}x${MyApp.depthScaledHeight}, Landscape: ${measuredWidth}x${measuredHeight}")
        bitmapMatrix = Matrix().apply {
          val bufferWidth = MyApp.depthScaledWidth
          val bufferHeight = MyApp.depthScaledHeight
          val bufferRect = RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
          val viewRect = RectF(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat())

          setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)
        }
      }
      else
      {
        Log.i(TAG, "Buffer: ${MyApp.depthScaledWidth}x${MyApp.depthScaledHeight}, Portrait: ${measuredWidth}x${measuredHeight}")
        bitmapMatrix = Matrix().apply {
          val bufferWidth = MyApp.depthScaledHeight
          val bufferHeight = MyApp.depthScaledWidth
          val bufferRect = RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
          val viewRect = RectF(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat())

          setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)
        }
      }
  }
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val height = MeasureSpec.getSize(heightMeasureSpec)
    Log.i(TAG, "onMeasure: before adjustment width: $width  height: $height")
    var adjustedWidth = width
    var adjustedHeight = height
    if (width / MyApp.depthScaledWidth.toFloat() * MyApp.depthScaledHeight.toFloat() > height) {
//      if (width / 16f * 9f > height) {
      adjustedWidth = (height / MyApp.depthScaledHeight.toFloat() * MyApp.depthScaledWidth.toFloat()).toInt()
    } else {
      adjustedHeight = (width / MyApp.depthScaledWidth.toFloat() * MyApp.depthScaledHeight.toFloat()).toInt()
    }
    Log.i(TAG, "onMeasure: after adjustment width: $adjustedWidth  height: $adjustedHeight")
    setMeasuredDimension(adjustedWidth, adjustedHeight)
    updateBitmapMatrix(adjustedWidth, adjustedHeight)
  }

//  @SuppressLint("DrawAllocation")
//  override fun onDraw(canvas: Canvas) {
//    super.onDraw(canvas)
//    canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
//  }

}