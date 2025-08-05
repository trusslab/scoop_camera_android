package com.example.tofcamera

import android.content.Context
import android.graphics.Canvas
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import kotlin.math.min

class CameraSurfaceView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val relativeRotation = MyApp.computeRelativeRotation(MyApp.mainCameraChars, context.display?.rotation ?: 0)
        Log.i("CameraSurfaceView", "relativeRotation: $relativeRotation")

        if (MyApp.mainPreviewWidth > 0f && MyApp.mainPreviewHeight > 0f) {
            /* Scale factor required to scale the preview to its original size on the x-axis. */
            val scaleX =
                if (relativeRotation % 180 == 0) {
                    width.toFloat() / MyApp.mainPreviewHeight
                } else {
                    width.toFloat() / MyApp.mainPreviewWidth
                }
            /* Scale factor required to scale the preview to its original size on the y-axis. */
            val scaleY =
                if (relativeRotation % 180 == 0) {
                    height.toFloat() / MyApp.mainPreviewWidth
                } else {
                    height.toFloat() / MyApp.mainPreviewHeight
                }

            /* Scale factor required to fit the preview to the SurfaceView size. */
            val finalScale = min(scaleX, scaleY)
            Log.i("CameraSurfaceView", "finalScale: $finalScale scaleX: $scaleX  scaleY: $scaleY  width: $width  height: $height  MyApp.mainPreviewWidth: ${MyApp.mainPreviewWidth}  MyApp.mainPreviewHeight: ${MyApp.mainPreviewHeight}  relativeRotation: $relativeRotation  context.display?.rotation: ${context.display?.rotation}")

            setScaleX(1 / scaleX * finalScale)
            setScaleY(1 / scaleY * finalScale)
        }
        setMeasuredDimension(width, height)
    }
}