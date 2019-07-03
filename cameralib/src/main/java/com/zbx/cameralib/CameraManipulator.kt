package com.zbx.cameralib

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.TextureView
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * An instance of CameraManipulator enables the client (the app uses this lib) to open a specific camera,
 * render the preview frame on a TextureView, and obtain frame data
 * @param context the Context of the client
 * @param cameraId the id of the camera the client is going to open
 */
class CameraManipulator(private val context: Context, private val cameraId: String) {
    private val TAG by lazy { CameraManipulator::class.java.simpleName }

    /** The TextureView the frame will render on. It is provided by the client */
    private var textureView: TextureView? = null

    /** The callback of getting frame data. The client processes the frame data by providing this callback */
    private var frameDataCallback: FrameDataCallback? = null

    /** The type of the frame data, it is also asked by the client. Default is NV21 which is required by ArcSoft */
    private var frameDataType: FrameDataType = FrameDataType.NV21

    /** Whether the width or height should be adjusted by the lib. In most cases, the textureView dimension
     *  need to be changed unless its aspect radio equals to a specific camera preview size */
    private var fixedViewWidth: Boolean = false
    private var fixedViewHeight: Boolean = false

    /**
     * A reference to the opened [CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [CameraCaptureSession] for camera preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * An [ImageReader] that read the image frame data
     */
    private var imageReader: ImageReader? = null

    /** The stream configuration map of the camera */
    private var configMap: StreamConfigurationMap? = null

    enum class FrameDataType {
        NV21   // Add more data types as needed
    }

    interface FrameDataCallback {
        fun onDataAvailable(frameData: ByteArray)
    }

    fun start() {
        startBackgroundThread()
        openCamera()
    }

    fun stop() {
        closeCamera()
        stopBackgroundThread()
    }

    /**
     * Open the camera
     * @return an integer indicates whether the opening is successful or
     * the failure cause
     */
    private fun openCamera(): Int {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission hasn't been granted")
            return PERMISSION_NOT_GRANTED
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cid in manager.cameraIdList) {
                if (cid != cameraId) {
                    continue
                }

                val characteristics = manager.getCameraCharacteristics(cid)
                configMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: return CAMERA_CHARACTERISTIC_FAILED

                // Wait for camera to open - 2.5 seconds is sufficient
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    throw RuntimeException("Time out waiting to lock camera opening.")
                }
                manager.openCamera(cameraId, stateCallback, backgroundHandler)

                Log.i(TAG, "Try opening the camera: $cameraId")
                return TRY_OPENING
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
            return CAMERA_ACCESS_EXCEPTION
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, e.toString())
            return CAMERA_API_NOT_SUPPORTED
        }

        Log.e(TAG, "Camera ID is invalid")
        return CAMERA_ID_INVALID
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Going through the initialisation of preview and frame data related elements
     * since those "set" routines are likely to be called before calling openCamera().
     * If it is not the case, this method would be essentially doing nothing at all
     */
    private fun goThroughInitialisation() {
        setTextureView(textureView, fixedViewWidth, fixedViewHeight)
        setFrameDataType(frameDataType)
        setFrameDataCallback(frameDataCallback)
    }

    /**
     * Set the texture view indicates the client asks the lib to render the preview frame
     * on this view ASAP. The lib will adjust the dimensions of the view based on the supported preview
     * sizes of the camera.
     * Currently, The texture view in most face recognition apps(usually in portrait mode)
     * would be designed as fully-expanded in width whereas the height can be adjustable.
     * @param textureView: the texture view on which the frames are going to be rendered.
     * If null, means no preview required from now on
     * @param fixedWidth: true if the width of the texture view should be kept
     * @param fixedHeight: true if the height of the texture view should be kept
     */
    fun setTextureView(textureView: TextureView?, fixedWidth: Boolean = true, fixedHeight: Boolean = false) {
        if (textureView == null) {
            this.textureView = null
            // Stop the preview
        } else {
            this.textureView = textureView
            fixedViewWidth = fixedWidth
            fixedViewHeight = fixedHeight
            // Start the preview
        }
    }

    /**
     * The client needs to implement FrameDataCallback if it managed to process the frame data
     * @param callback the client implementation of FrameDataCallback
     * If null, means no frame data is required any more
     */
    fun setFrameDataCallback(callback: FrameDataCallback?) {
        if (callback == null) {
            frameDataCallback = null
            // Stop the frame feedback
        } else {
            frameDataCallback = callback
            // start the frame feedback
        }
    }

    /**
     * Set the frame data type the client wants to obtain, e.g. NV21
     */
    fun setFrameDataType(dataType: FrameDataType) {
        frameDataType = dataType
    }

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            Log.i(TAG, "Camera opened: $cameraId")
            cameraOpenCloseLock.release()
            this@CameraManipulator.cameraDevice = cameraDevice
            goThroughInitialisation()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            Log.i(TAG, "Camera disconnected: $cameraId")
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraManipulator.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            Log.e(TAG, "Camera state error: $error")
            onDisconnected(cameraDevice)
        }

    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground#$THREAD_NUM++").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }

    }

    companion object {
        /**
         * Error codes of opening the camera
         */
        const val TRY_OPENING = 0  // No error occurred so far, trying to open the camera
        const val PERMISSION_NOT_GRANTED = -1
        const val CAMERA_ID_INVALID = -2
        const val CAMERA_CHARACTERISTIC_FAILED = -3
        const val CAMERA_ACCESS_EXCEPTION = -4
        const val CAMERA_API_NOT_SUPPORTED = -5

        private var THREAD_NUM = 0  // The sequence number of the background handler thread
    }
}