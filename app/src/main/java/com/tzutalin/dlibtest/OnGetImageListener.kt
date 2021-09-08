/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tzutalin.dlibtest

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.*
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatTextView
import com.tzutalin.dlib.Constants
import com.tzutalin.dlib.FaceDet
import com.tzutalin.dlib.VisionDetRet
import junit.framework.Assert
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
class OnGetImageListener : OnImageAvailableListener {

    private var screenRotation = 90
    private var previewWidth = 0
    private var previewHeight = 0
    private lateinit var yuvBytes: Array<ByteArray?>
    private var rgbBytes: IntArray? = null
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var isComputing = false
    private var inferenceHandler: Handler? = null
    private var context: Context? = null
    private lateinit var faceDet: FaceDet
    private var transparentTitleView: AppCompatTextView? = null
    private lateinit var window: FloatingCameraWindow
    private lateinit var faceLandmarkPaint: Paint

    fun initialize(
        context: Context?,
        assetManager: AssetManager?,
        scoreView: AppCompatTextView?,
        handler: Handler?
    ) {
        this.context = context
        transparentTitleView = scoreView
        inferenceHandler = handler
        faceDet = FaceDet(Constants.getFaceShapeModelPath())
        window = FloatingCameraWindow(this.context)
        faceLandmarkPaint = Paint()
        faceLandmarkPaint.color = Color.GREEN
        faceLandmarkPaint.strokeWidth = 2f
        faceLandmarkPaint.style = Paint.Style.STROKE
    }

    fun deInitialize() {
        synchronized(this@OnGetImageListener) {
            faceDet.release()
            window.release()
        }
    }

    private fun drawResizedBitmap(src: Bitmap?, dst: Bitmap?) {
        val getOrient: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context!!.display
        } else {
            (context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }

        var orientation = Configuration.ORIENTATION_UNDEFINED
        val point = Point()
        getOrient?.getSize(point)
        val screenWidth = point.x
        val screenHeight = point.y
        Log.d(TAG, String.format("screen size (%d,%d)", screenWidth, screenHeight))

        if (screenWidth < screenHeight) {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenRotation = 90
        } else {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenRotation = 0
        }

        Assert.assertEquals(dst!!.width, dst.height)

        val minDim = min(src!!.width, src.height).toFloat()
        val matrix = Matrix()

        // We only want the center square out of the original rectangle.
        val translateX = -max(0f, (src.width - minDim) / 2)
        val translateY = -max(0f, (src.height - minDim) / 2)
        matrix.preTranslate(translateX, translateY)
        val scaleFactor = dst.height / minDim
        matrix.postScale(scaleFactor, scaleFactor)

        // Rotate around the center if necessary.
        if (screenRotation != 0) {
            matrix.postTranslate(-dst.width / 2.0f, -dst.height / 2.0f)
            matrix.postRotate(screenRotation.toFloat())
            matrix.postTranslate(dst.width / 2.0f, dst.height / 2.0f)
        }
        val canvas = Canvas((dst))
        canvas.drawBitmap((src), matrix, null)
    }

    @SuppressLint("SetTextI18n")
    override fun onImageAvailable(reader: ImageReader) {

        var image: Image? = null

        try {

            image = reader.acquireLatestImage()

            if (image == null) {
                return
            }

            // No mutex needed as this method is not reentrant.
            if (isComputing) {
                image.close()
                return
            }

            isComputing = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes

            // Initialize the storage bitmaps once when the resolution is known.
            if (previewWidth != image.width || previewHeight != image.height) {
                previewWidth = image.width
                previewHeight = image.height
                Log.d(TAG,"Initializing at size $previewWidth x $previewHeight")
                rgbBytes = IntArray(previewWidth * previewHeight)
                rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
                yuvBytes = arrayOfNulls(planes.size)
                for (i in planes.indices) {
                    yuvBytes[i] = ByteArray(planes[i].buffer.capacity())
                }
            }

            for (i in planes.indices) {
                planes[i].buffer[yuvBytes[i]]
            }

            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            ImageUtils.convertYUV420ToARGB8888(
                yuvBytes[0],
                yuvBytes[1],
                yuvBytes[2],
                rgbBytes,
                previewWidth,
                previewHeight,
                yRowStride,
                uvRowStride,
                uvPixelStride,
                false
            )

            image.close()

        } catch (e: Exception) {
            image?.close()
            Log.e(TAG, "Exception!", e)
            Trace.endSection()
            return
        }

        rgbFrameBitmap?.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        drawResizedBitmap(rgbFrameBitmap, croppedBitmap)

        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap)
        }

        inferenceHandler?.post {
            if (!File(Constants.getFaceShapeModelPath()).exists()) {
                transparentTitleView?.text = "Copying landmark model to " + Constants.getFaceShapeModelPath()
                FileUtils.copyFileFromRawToOthers(
                    context!!,
                    R.raw.shape_predictor_68_face_landmarks,
                    Constants.getFaceShapeModelPath()
                )
            }
            val startTime = System.currentTimeMillis()
            var results: List<VisionDetRet>?

            synchronized(this@OnGetImageListener) {
                results = faceDet.detect((croppedBitmap)!!)
            }

            val endTime = System.currentTimeMillis()
            transparentTitleView?.text = "Time cost: " + ((endTime - startTime) / 1000f).toString() + " sec"

            // Draw on bitmap
            if (results != null) {
                for (ret: VisionDetRet in results!!) {
                    val resizeRatio = 1.0f
                    val bounds = Rect()
                    bounds.left = (ret.left * resizeRatio).toInt()
                    bounds.top = (ret.top * resizeRatio).toInt()
                    bounds.right = (ret.right * resizeRatio).toInt()
                    bounds.bottom = (ret.bottom * resizeRatio).toInt()
                    val canvas = Canvas(croppedBitmap!!)
                    canvas.drawRect(bounds, (faceLandmarkPaint))

                    Log.e(TAG, "Landmarks: ${ret.faceLandmarks.size}")

                    // Draw landmark
                    val landmarks = ret.faceLandmarks
                    for (point: Point in landmarks) {
                        val pointX = (point.x * resizeRatio)
                        val pointY = (point.y * resizeRatio)
                        canvas.drawCircle(pointX, pointY, 2f, faceLandmarkPaint)
                    }
                }
            }
            window.setRGBBitmap(croppedBitmap)
            isComputing = false
        }

        Trace.endSection()
    }

    companion object {
        private const val SAVE_PREVIEW_BITMAP = false
        private const val INPUT_SIZE = 224
        private const val TAG = "OnGetImageListener"
    }
}