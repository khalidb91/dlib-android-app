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

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.*
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import hugo.weaving.DebugLog
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sign

class CameraConnectionFragment : Fragment() {

    private var scoreView: AppCompatTextView? = null

    companion object {
        /**
         * The camera preview size will be chosen to be the smallest frame by pixel size capable of
         * containing a DESIRED_SIZE x DESIRED_SIZE square.
         */
        private const val MINIMUM_PREVIEW_SIZE: Int = 320
        private const val TAG: String = "CameraConnectionFragment"

        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS: SparseIntArray = SparseIntArray()
        private const val FRAGMENT_DIALOG: String = "dialog"

        /**
         * Given `choices` of `Size`s supported by a camera, chooses the smallest one whose
         * width and height are at least as large as the respective requested values, and whose aspect
         * ratio matches with the specified value.
         *
         * @param choices     The list of sizes that the camera supports for the intended output class
         * @param width       The minimum desired width
         * @param height      The minimum desired height
         * @param aspectRatio The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @SuppressLint("LongLogTag")
        @DebugLog
        private fun chooseOptimalSize(
            choices: Array<Size>, width: Int, height: Int, aspectRatio: Size
        ): Size {
            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough: MutableList<Size> = ArrayList()
            for (option: Size in choices) {
                if (option.height >= MINIMUM_PREVIEW_SIZE && option.width >= MINIMUM_PREVIEW_SIZE) {
                    Log.i(TAG, "Adding size: " + option.width + "x" + option.height)
                    bigEnough.add(option)
                } else {
                    Log.i(TAG, "Not adding size: " + option.width + "x" + option.height)
                }
            }

            // Pick the smallest of those, assuming we found any
            return if (bigEnough.size > 0) {
                val chosenSize: Size = Collections.min(bigEnough, CompareSizesByArea())
                Log.i(TAG, "Chosen size: " + chosenSize.width + "x" + chosenSize.height)
                chosenSize
            } else {
                Log.i(TAG, "Couldn't find any suitable preview size")
                choices[0]
            }
        }

        fun newInstance(): CameraConnectionFragment {
            return CameraConnectionFragment()
        }

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }

    /**
     * [android.view.TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    /**
     * ID of the current [CameraDevice].
     */
    private var cameraId: String? = null

    /**
     * An [AutoFitTextureView] for camera preview.
     */
    private var textureView: AutoFitTextureView? = null

    /**
     * A [CameraCaptureSession] for camera preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * A reference to the opened [CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private var previewSize: Size? = null

    /**
     * [android.hardware.camera2.CameraDevice.StateCallback]
     * is called when [CameraDevice] changes its state.
     */
    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            onGetPreviewListener.deInitialize()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            activity?.finish()
            onGetPreviewListener.deInitialize()
        }
    }

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * An additional thread for running inference so as not to block the camera.
     */
    private var inferenceThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var inferenceHandler: Handler? = null

    /**
     * An [ImageReader] that handles preview frame capture.
     */
    private var previewReader: ImageReader? = null

    /**
     * [android.hardware.camera2.CaptureRequest.Builder] for the camera preview
     */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /**
     * [CaptureRequest] generated by [.previewRequestBuilder]
     */
    private var previewRequest: CaptureRequest? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock: Semaphore = Semaphore(1)

    /**
     * Shows a [Toast] on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        activity?.runOnUiThread {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.camera_connection_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById<View>(R.id.texture) as AutoFitTextureView?
        scoreView = view.findViewById<View>(R.id.results) as AppCompatTextView?
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        textureView?.let { autoFitTextureView ->
            if(autoFitTextureView.isAvailable){
                openCamera(autoFitTextureView.width, autoFitTextureView.height)
            } else {
                autoFitTextureView.surfaceTextureListener =surfaceTextureListener
            }
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @DebugLog
    @SuppressLint("LongLogTag")
    private fun setUpCameraOutputs(width: Int, height: Int) {

        val activity: FragmentActivity? = activity
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {

            val cameraFaceTypeMap: SparseArray<Int?> = SparseArray()

            // Check the facing types of camera devices
            for (cameraId: String in manager.cameraIdList) {

                val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(cameraId)

                val facing: Int? = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(
                            CameraCharacteristics.LENS_FACING_FRONT,
                            cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT)!! + 1
                        )
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, 1)
                    }
                }

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(
                            CameraCharacteristics.LENS_FACING_BACK,
                            cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK)!! + 1
                        )
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, 1)
                    }
                }
            }

            val facingBackCamera: Int? = cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK)

            for (cameraId: String in manager.cameraIdList) {
                val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(cameraId)
                val facing: Int? = characteristics.get(CameraCharacteristics.LENS_FACING)

                // If facing back camera or facing external camera exist, we won't use facing front camera
                if (facingBackCamera != null && facingBackCamera > 0) {
                    // We don't use a front facing camera in this sample if there are other camera device facing types
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        continue
                    }
                }

                val map: StreamConfigurationMap =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

                // For still image captures, we use the largest available size.
                val largest: Size = Collections.max(
                    listOf(*map.getOutputSizes(ImageFormat.YUV_420_888)),
                    CompareSizesByArea()
                )

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    width,
                    height,
                    largest
                )

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView?.setAspectRatio(
                        previewSize!!.width,
                        previewSize!!.height
                    )
                } else {
                    textureView?.setAspectRatio(
                        previewSize!!.height,
                        previewSize!!.width
                    )
                }
                this@CameraConnectionFragment.cameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Exception!", e)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /**
     * Opens the camera specified by [CameraConnectionFragment.cameraId].
     */
    @SuppressLint("LongLogTag")
    @DebugLog
    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val manager: CameraManager =
            requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "checkSelfPermission CAMERA")
            }
            manager.openCamera((cameraId)!!, stateCallback, backgroundHandler)
            Log.d(TAG, "open Camera")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Exception!", e)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    @DebugLog
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != previewReader) {
                previewReader!!.close()
                previewReader = null
            }
            onGetPreviewListener?.deInitialize()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    @DebugLog
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
        inferenceThread = HandlerThread("InferenceThread")
        inferenceThread?.start()
        inferenceHandler = Handler(inferenceThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    @SuppressLint("LongLogTag")
    @DebugLog
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        inferenceThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            inferenceThread?.join()
            inferenceThread = null
            inferenceThread = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "error", e)
        }
    }

    private val onGetPreviewListener: OnGetImageListener = OnGetImageListener()
    private val captureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
        }
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    @SuppressLint("LongLogTag")
    @DebugLog
    private fun createCameraPreviewSession() {
        try {
            val texture: SurfaceTexture? = textureView?.surfaceTexture
            assert(texture != null)

            // We configure the size of default buffer to be the size of camera preview we want.
            texture?.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface: Surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)
            Log.i(TAG, "Opening camera preview: ${previewSize?.width}x${previewSize?.height}")

            // Create the reader for the preview frames.
            previewReader = previewSize?.let {
                ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 2)
            }

            previewReader?.setOnImageAvailableListener(onGetPreviewListener, backgroundHandler)
            previewReader?.let { previewRequestBuilder?.addTarget(it.surface) }

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(
                listOf(surface, previewReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder!!.build()
                            captureSession!!.setRepeatingRequest(
                                previewRequest!!, captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Exception!", e)
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        showToast("Failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Exception!", e)
        }
        onGetPreviewListener.initialize(
            activity?.applicationContext,
            activity?.assets,
            scoreView,
            inferenceHandler
        )
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    @DebugLog
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity: Activity? = activity
        if ((null == textureView) || (null == previewSize) || (null == activity)) {
            return
        }
        val rotation: Int = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX: Float = viewRect.centerX()
        val centerY: Float = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale: Float = max(
                viewHeight.toFloat() / previewSize!!.height,
                viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        textureView?.setTransform(matrix)
    }

    /**
     * Compares two `Size`s based on their areas.
     */
    internal class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return (lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height).sign
        }
    }

    /**
     * Shows an error message dialog.
     */
    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val activity: FragmentActivity? = activity
            return AlertDialog.Builder(activity)
                .setMessage(arguments?.getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    activity?.finish()
                }
                .create()
        }

        companion object {

            private const val ARG_MESSAGE: String = "message"

            fun newInstance(message: String?): ErrorDialog {
                val dialog = ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }

        }
    }
}