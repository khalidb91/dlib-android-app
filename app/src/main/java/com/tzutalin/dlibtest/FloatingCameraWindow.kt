/*
 *  Copyright (C) 2016-present Tzuta Lin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  imitations under the License.
 */
package com.tzutalin.dlibtest

import android.content.Context
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.UiThread
import java.lang.ref.WeakReference

/**
 * Created by Tzutalin on 2016/5/25
 */
class FloatingCameraWindow constructor(private val context: Context?) {

    companion object {
        private const val TAG: String = "FloatingCameraWindow"
        private const val MOVE_THRESHOLD: Int = 10
        private const val DEBUG: Boolean = true
    }

    private var windowParam: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var rootView: FloatCamView? = null
    private val uiHandler: Handler = Handler(Looper.getMainLooper())
    private var windowWidth: Int
    private var windowHeight: Int
    private var screenMaxWidth: Int = 0
    private var screenMaxHeight: Int = 0
    private var scaleWidthRatio: Float = 1.0f
    private var scaleHeightRatio: Float = 1.0f

    init {
        // Get screen max size
        val size = Point()
        val display = (context?.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getSize(size)
        screenMaxWidth = size.x
        screenMaxHeight = size.y
        // Default window size
        windowWidth = screenMaxWidth / 2
        windowHeight = screenMaxHeight / 2
        windowWidth = if (windowWidth in 1 until screenMaxWidth) windowWidth else screenMaxWidth
        windowHeight = if (windowHeight in 1 until screenMaxHeight) windowHeight else screenMaxHeight
    }

    constructor(context: Context?, windowWidth: Int, windowHeight: Int) : this(context) {

        if ((windowWidth < 0) || (windowWidth > screenMaxWidth) || (windowHeight < 0) || (windowHeight > screenMaxHeight)) {
            throw IllegalArgumentException("Window size is illegal")
        }

        scaleWidthRatio = windowWidth.toFloat() / this.windowHeight
        scaleHeightRatio = windowHeight.toFloat() / this.windowHeight
        if (DEBUG) {
            Log.d(TAG, "mScaleWidthRatio: $scaleWidthRatio")
            Log.d(TAG, "mScaleHeightRatio: $scaleHeightRatio")
        }
        this.windowWidth = windowWidth
        this.windowHeight = windowHeight
    }

    private fun init() {
        uiHandler.postAtFrontOfQueue {
            if (windowManager == null || rootView == null) {
                windowManager = context?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                rootView = FloatCamView(this@FloatingCameraWindow)
                windowManager?.addView(rootView, initWindowParameter())
            }
        }
    }

    fun release() {
        uiHandler.postAtFrontOfQueue {
            if (windowManager != null) {
                windowManager?.removeViewImmediate(rootView)
                rootView = null
            }
            uiHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun initWindowParameter(): WindowManager.LayoutParams {
        windowParam = WindowManager.LayoutParams()
        windowParam?.apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            format = 1
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            alpha = 1.0f
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 0
            width = windowWidth
            height = windowHeight
        }
        return windowParam as WindowManager.LayoutParams
    }

    fun setRGBBitmap(rgb: Bitmap?) {
        checkInit()
        uiHandler.post { rootView?.setRGBImageView(rgb) }
    }

    fun setFPS(fps: Float) {
        checkInit()
        uiHandler.post {
            checkInit()
            rootView?.setFPS(fps)
        }
    }

    fun setMoreInformation(info: String?) {
        checkInit()
        uiHandler.post {
            checkInit()
            rootView?.setMoreInformation(info)
        }
    }

    private fun checkInit() {
        if (rootView == null) {
            init()
        }
    }

    @UiThread
    private inner class FloatCamView constructor(
        window: FloatingCameraWindow
    ) : FrameLayout(window.context!!) {

        private val weakRef: WeakReference<FloatingCameraWindow> = WeakReference(window)
        private var lastX: Int = 0
        private var lastY: Int = 0
        private var firstX: Int = 0
        private var firstY: Int = 0
        private val layoutInflater: LayoutInflater =
            window.context!!.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        private val colorView: ImageView
        private val fpsText: TextView?
        private val infoText: TextView?
        private var isMoving: Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    firstX = lastX
                    firstY = lastY
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX: Int = event.rawX.toInt() - lastX
                    val deltaY: Int = event.rawY.toInt() - lastY
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    val totalDeltaX: Int = lastX - firstX
                    val totalDeltaY: Int = lastY - firstY
                    if ((isMoving
                                || (Math.abs(totalDeltaX) >= MOVE_THRESHOLD
                                ) || (Math.abs(totalDeltaY) >= MOVE_THRESHOLD))
                    ) {
                        isMoving = true
                        val windowMgr: WindowManager? = weakRef.get()!!.windowManager
                        val parm: WindowManager.LayoutParams? = weakRef.get()!!.windowParam
                        if (event.pointerCount == 1 && windowMgr != null) {
                            parm!!.x -= deltaX
                            parm!!.y -= deltaY
                            windowMgr.updateViewLayout(this, parm)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> isMoving = false
            }
            return true
        }

        fun setRGBImageView(rgb: Bitmap?) {
            if (rgb != null && !rgb.isRecycled) {
                colorView.setImageBitmap(rgb)
            }
        }

        fun setFPS(fps: Float) {
            if (fpsText != null) {
                if (fpsText.visibility == GONE) {
                    fpsText.visibility = VISIBLE
                }
                fpsText.text = String.format("FPS: %.2f", fps)
            }
        }

        fun setMoreInformation(info: String?) {
            if (infoText != null) {
                if (infoText.visibility == GONE) {
                    infoText.visibility = VISIBLE
                }
                infoText.text = info
            }
        }

        init {
            val body: FrameLayout = this
            val floatView: View = layoutInflater.inflate(R.layout.cam_window_view, body, true)
            colorView = floatView.findViewById<View>(R.id.imageView_c) as ImageView
            fpsText = floatView.findViewById<View>(R.id.fps_textview) as TextView?
            infoText = floatView.findViewById<View>(R.id.info_textview) as TextView?
            fpsText?.visibility = GONE
            infoText?.visibility = GONE
            val colorMaxWidth: Int = (windowWidth * window.scaleWidthRatio).toInt()
            val colorMaxHeight: Int = (windowHeight * window.scaleHeightRatio).toInt()
            colorView.layoutParams.width = colorMaxWidth
            colorView.layoutParams.height = colorMaxHeight
        }
    }

}