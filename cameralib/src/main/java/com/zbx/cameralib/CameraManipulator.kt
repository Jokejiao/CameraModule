package com.zbx.cameralib

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.properties.Delegates
import kotlin.reflect.KProperty


/**
 * Author: Ke Jiao (Alex)
 * An instance of CameraManipulator enables the client (the app uses this lib) to open a specific camera,
 * render the preview frame on a TextureView, and obtain frame data
 * Note that frame catching causes frequent GC while preview gives rise to an increasing native heap
 * allocation(Yet can be GCed eventually). So far, no memory leak has detected
 * @param builder the builder object of the manipulator
 */
class CameraManipulator private constructor(builder: Builder) {
    /** The TextureView the frame will be rendered on. It is provided by the client */
    private var textureView: AutoFitTextureView? = null

    /** The callback of getting frame data. The client processes the frame data by providing this callback */
    private var frameDataCallback: FrameDataCallback? = null

    /** The type of the frame data, it is also asked by the client. Default is NV21 which is required by ArcSoft */
    private var frameDataType: FrameDataType = FrameDataType.NV21

    /** A reference to the opened [CameraDevice] */
    private var cameraDevice: CameraDevice? = null

    /** A [Semaphore] to prevent the app from exiting before closing the camera. */
    private val cameraOpenCloseLock = Semaphore(1)

    /** A [Handler] for running tasks in the background. */
    private var backgroundHandler: Handler? = null

    /** An additional thread for running tasks that shouldn't block the UI. */
    private var backgroundThread: HandlerThread? = null

    /** A [CameraCaptureSession] for camera preview. */
    private var captureSession: CameraCaptureSession? = null

    /** An [ImageReader] that read the image frame data */
    private var imageReader: ImageReader? = null

    /** Camera callback to notify the camera states */
    private var cameraCallback: CameraCallback? = null

    /** The Android context of the client */
    private var context: Context

    /** The ID of the camera being manipulate */
    private var cameraId: String

    /** The device  rotation */
    private var rotation: Int

    /** Some devices need to have an additional rotation */
    private var additionalRotation = ROTATION_0

    /** Orientation of the camera sensor */
    private var sensorOrientation: Int? = 0

    /** A specific preview size the client wants to have */
    private var specificPreviewSize: Size? = null

    /** A specific frame size the client wants to have */
    private var frameSize: Size = DEFAULT_FRAME_SIZE

    /** The [android.util.Size] of camera preview. */
    private lateinit var previewSize: Size

    /** [CaptureRequest.Builder] for the camera preview */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    /** Flip the image over horizontally */
    private var flipOver = false

    /** True for displaying the central region of the image, false for displaying the entire image  */
    private var neverDistorted = true

    /** The textureView surface for preview */
    private var viewSurface: Surface? by Delegates.observable(null, ::surfaceChangeHandler)

    /** The ImageReader surface for getting frame data */
    private var readerSurface: Surface? by Delegates.observable(null, ::surfaceChangeHandler)

    /** Take down the surface list of the CaptureSession. The session can be reused if
    it already contains all surfaces required as output (Just add/remove the CaptureRequest's target(s) and
    renew the request by invoking setRepeatingRequest) */
    private val sessionSurfaceList = mutableListOf<Surface>()
    private val currentSessionSurfaceList = mutableListOf<Surface>()

    /** Throttle the creation of CaptureSession, since it is asynchronous. A quickly repeat recreation may cause
    a closed session being configured and throw IllegalStateException */
    @Volatile
    private var sessionCreationInProgress = false

    init {
        context = builder.context!!
        textureView = builder.textureView
        textureView?.setTransformRoutine { width, height ->
            configureTransform(width, height)
        }
        flipOver = builder.flipOver
        if (flipOver) textureView?.scaleX = -1f
        cameraId = builder.cameraId
        cameraCallback = builder.cameraCallback
        frameDataCallback = builder.frameDataCallback
        rotation = builder.rotation
        additionalRotation = builder.additionalRotation
        specificPreviewSize = builder.specificPreviewSize
        frameSize = builder.frameSize
        neverDistorted = builder.neverDistorted
    }

    enum class FrameDataType {
        NV21   // Add more data types as needed
    }

    interface FrameDataCallback {
        fun onDataAvailable(frameData: ByteArray? = null)
    }

    interface CameraCallback {
        fun onCameraOpened(cameraId: String)
        fun onCameraPreviewSize(cameraId: String, previewSize: Size)
        fun onCameraClosed(cameraId: String)
        fun onCameraError(cameraId: String, errorMsg: String)
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
    private fun openCamera() {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission hasn't been granted")
            cameraCallback?.onCameraError(cameraId, PERMISSION_NOT_GRANTED)
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (!manager.cameraIdList.contains(cameraId)) {
            Log.e(TAG, "Camera ID is invalid")
            cameraCallback?.onCameraError(cameraId, CAMERA_ID_INVALID)
            return
        }

        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            Log.i(TAG, "Try opening the camera: $cameraId")
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
            return
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
            cameraCallback?.onCameraError(cameraId, CAMERA_ACCESS_EXCEPTION)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, e.toString())
            cameraCallback?.onCameraError(cameraId, CAMERA_API_NOT_SUPPORTED)
        }
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
            cameraCallback?.onCameraClosed(cameraId)
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
    private fun setPreviewAndFrame() {
        setPreviewOn(textureView)
        setFrameDataType(frameDataType)
        setFrameDataCallback(frameDataCallback)
    }

    /**
     * Manipulate the CaptureRequest and CaptureSession in terms of surfaces changes
     * @param prop the surface property being changed
     * @param old old property value
     * @param new new property value
     */
    private fun surfaceChangeHandler(prop: KProperty<*>, old: Surface?, new: Surface?) {
        // Remove the old surface from and add the new to the request builder
        if (old != null) previewRequestBuilder.removeTarget(old)
        if (new != null) previewRequestBuilder.addTarget(new)

        // 3 cases need to be managed. (1) old == null && new != null (2) old != null && new == null
        // (3) old != null && new != null
        if (old == null && new != null) {
            Log.d(TAG, "$prop.name surface is added")
            if (!sessionSurfaceList.contains(new)) {
                sessionSurfaceList.add(new)
            }
        } else if (old != null) {
            sessionSurfaceList.remove(old)
            old.release()
            if (new == null) Log.d(TAG, "$prop.name surface is removed") else sessionSurfaceList.add(new)
        }

        // Abort the CaptureSession if there's no surface
        // As per the doc, don't close the session for more efficient reuse
        if (viewSurface == null && readerSurface == null) {
            captureSession?.abortCaptures()
            return
        }

        if (currentSessionSurfaceList.containsAll(sessionSurfaceList)) {
            // reset repeating request
            Log.d(TAG, "reset repeating request")
            val previewRequest = previewRequestBuilder.build()
            captureSession?.setRepeatingRequest(
                previewRequest,
                null, backgroundHandler
            )
        } else {
            // recreate capture session
            Log.d(TAG, "recreate capture session")
            if (sessionCreationInProgress) return
            sessionCreationInProgress = true
            captureSession = null

            try {
                // Here, we create a CameraCaptureSession for camera preview.
                cameraDevice?.createCaptureSession(
                    sessionSurfaceList,
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (cameraDevice == null) return

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            sessionCreationInProgress = false

                            // Save current session surface list
                            currentSessionSurfaceList.clear()
                            currentSessionSurfaceList.addAll(sessionSurfaceList)

                            try {
                                // Finally, we start displaying the camera preview.
                                val previewRequest = previewRequestBuilder.build()
                                captureSession?.setRepeatingRequest(
                                    previewRequest,
                                    null, backgroundHandler
                                )
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, e.toString())
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure camera preview")
                            sessionCreationInProgress = false
                            cameraCallback?.onCameraError(cameraId, CAMERA_CONFIG_FAILED)
                        }
                    }, null
                )
            } catch (e: CameraAccessException) {
                Log.e(TAG, e.toString())
            }
        }
    }

    /**
     * This is a callback object for the [ImageReader]. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        val image = it.acquireLatestImage()
        image ?: return@OnImageAvailableListener  // Take place at Capture Session recreation

        // At the stage, only NV21 is supported
        if (frameDataType == FrameDataType.NV21) {
            // This conversion takes roughly 1-2 ms on HuaWei Note10 with a 320*240 image
            val dataFromImage = DataUtils.getDataFromImage(image, DataUtils.COLOR_FormatNV21)
            frameDataCallback?.onDataAvailable(dataFromImage)
        }

        image.close()
    }

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            startPreview(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            /**
             * If textureView.setAspectRatio() did change its size, configureTransform() must be called
             * again to do the correct matrix transformation
             */
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        }
    }

    /**
     * Get a list of sizes compatible with SurfaceTexture to use as an output.
     */
    fun getSupportedOutputSizes(): Array<Size>? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                cameraCallback?.onCameraError(cameraId, CAMERA_CHARACTERISTIC_FAILED)
                return null
            }

            return map.getOutputSizes(SurfaceTexture::class.java)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
            cameraCallback?.onCameraError(cameraId, CAMERA_ACCESS_EXCEPTION)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, e.toString())
            cameraCallback?.onCameraError(cameraId, CAMERA_API_NOT_SUPPORTED)
        }

        return null
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)

            // Find out if we need to swap dimension to get the preview size relative to sensor
            // coordinate.
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val swappedDimensions = areDimensionsSwapped()
            val rotatedPreviewWidth = if (swappedDimensions) height else width
            val rotatedPreviewHeight = if (swappedDimensions) width else height

            val outputSizes = getSupportedOutputSizes()
            if (outputSizes.isNullOrEmpty()) return
            // Danger, W.R.! Attempting to use too large a preview size could exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(
                outputSizes,
                rotatedPreviewWidth, rotatedPreviewHeight,
                additionalRotation, specificPreviewSize
            )
            cameraCallback?.onCameraPreviewSize(cameraId, previewSize)
            // We fit the aspect ratio of TextureView to the size of preview we picked.
            // Be mindful of that setAspectRatio->requestLayout->onMeasure(async) would be called later than
            // configureTransform(). Thus configureTransform() must be called once again in onSurfaceTextureSizeChanged
            // to make things right
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView?.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView?.setAspectRatio(previewSize.height, previewSize.width)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
            cameraCallback?.onCameraError(cameraId, CAMERA_ACCESS_EXCEPTION)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, e.toString())
            cameraCallback?.onCameraError(cameraId, CAMERA_API_NOT_SUPPORTED)
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     * Be mindful of the image correctness depends on the gravity sensor
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()

        val xTranslation = textureView?.getXTranslation() ?: 0
        val yTranslation = textureView?.getYTranslation() ?: 0

        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        var targetRect = RectF(0f, 0f, viewHeight.toFloat(), viewWidth.toFloat())

        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val totalRotation = rotationMap.getValue(rotation).plus(additionalRotation)

        // Two different transformational strategies in terms of the rotation.
        // From the logic of AutoFitTextureView, xTranslation and yTranslation are invariably minus or zero
        if (totalRotation % 180 != 0) {
            with(matrix) {
                targetRect.let {
                    it.offset(centerX - it.centerX(), centerY - it.centerY())
                    setRectToRect(viewRect, it, Matrix.ScaleToFit.FILL)
                }

                if (neverDistorted) {
                    if (xTranslation < 0) {
                        val refViewHeight = viewWidth * viewHeight / (viewWidth + xTranslation)
                        var offset = (refViewHeight - viewHeight) / 2
                        targetRect.apply {
                            left -= offset
                            right += offset
                            setRectToRect(viewRect, this, Matrix.ScaleToFit.FILL)
                        }
                    }

                    if (yTranslation < 0) {
                        val refViewWidth = viewWidth * viewHeight / (viewHeight + yTranslation)
                        var offset = (refViewWidth - viewWidth) / 2
                        targetRect.apply {
                            top -= offset
                            bottom += offset
                            setRectToRect(viewRect, this, Matrix.ScaleToFit.FILL)
                        }
                    }
                }

                postRotate(totalRotation.toFloat(), centerX, centerY)
                textureView?.setTransform(this)
                return
            }
        }

        with(matrix) {
            if (neverDistorted) {
                targetRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

                if (xTranslation < 0) {
                    val refViewHeight = viewWidth * viewHeight / (viewWidth + xTranslation)
                    var offset = (refViewHeight - viewHeight) / 2
                    targetRect.apply {
                        top -= offset
                        bottom += offset
                        setRectToRect(viewRect, this, Matrix.ScaleToFit.FILL)
                    }
                }

                if (yTranslation < 0) {
                    val refViewWidth = viewWidth * viewHeight / (viewHeight + yTranslation)
                    var offset = (refViewWidth - viewWidth) / 2
                    targetRect.apply {
                        left -= offset
                        right += offset
                        setRectToRect(viewRect, this, Matrix.ScaleToFit.FILL)
                    }
                }
            }

            postRotate(totalRotation.toFloat(), centerX, centerY)
            textureView?.setTransform(this)
        }
    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param rotation The current rotation of the display
     *
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(): Boolean {
        var swappedDimensions = false
        when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $rotation")
            }
        }
        return swappedDimensions
    }

    /**
     * Set the texture view indicates the client asks the lib to render the preview frame
     * on this view ASAP. The lib will adjust the dimensions of the view based on the supported preview
     * sizes of the camera.
     * Currently, The texture view in most face recognition apps(usually in portrait mode)
     * would be designed as fully-expanded in width whereas the height can be adjustable.
     * @param textureView: the texture view on which the frames are going to be rendered.
     * If null, means no preview required from now on
     */
    @Synchronized
    fun setPreviewOn(textureView: AutoFitTextureView?) {
        if (textureView == null) {
            if (flipOver) this.textureView?.scaleX = -1f // reset the flip-over
            this.textureView?.setTransformRoutine(null) // The view doesn't need transformation any more
            this.textureView = null
            // Stop the preview
            viewSurface = null
        } else {
            if (this.textureView != null) {  // Replace the old textureView
                if (flipOver) this.textureView?.scaleX = -1f // reset the flip-over
                this.textureView?.setTransformRoutine(null)
            }

            this.textureView = textureView
            if (flipOver) this.textureView?.scaleX = -1f // set the flip-over
            textureView.setTransformRoutine { width, height ->
                configureTransform(width, height)
            }

            // Start the preview
            if (textureView.isAvailable) {
                startPreview(textureView.width, textureView.height)
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
        }
    }

    /**
     * Choose the optimal preview size
     * Resize the textureView in terms of the preview aspect ratio
     * Transform the textureView content matrix to display well-fitted camera images
     * Create camera preview session and set the preview repeating request
     * @param viewWidth width of the texture view
     * @param viewHeight height of the texture view
     */
    private fun startPreview(viewWidth: Int, viewHeight: Int) {
        setUpCameraOutputs(viewWidth, viewHeight)
        configureTransform(viewWidth, viewHeight)

        val texture = textureView?.surfaceTexture
        // We configure the size of default buffer to be the size of camera preview we want.
        texture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        // This is the output Surface we need to start preview.
        viewSurface = Surface(texture)  // Throw IllegalArgumentException if texture were null
    }

    /**
     * The client needs to implement FrameDataCallback if it managed to process the frame data
     * @param callback the client implementation of FrameDataCallback
     * If null, means no frame data is required any more
     */
    @Synchronized
    fun setFrameDataCallback(callback: FrameDataCallback?) {
        if (callback == null) {
            frameDataCallback = null
            // Stop the frame feedback
            readerSurface = null
            imageReader?.close()
            imageReader = null
        } else {
            // Just replace the existing callback and return
            if (frameDataCallback != null) {
                frameDataCallback = callback
                return
            }

            frameDataCallback = callback
            // Create the image reader
            imageReader = ImageReader.newInstance(
                frameSize.width, frameSize.height,
                ImageFormat.YUV_420_888, IMAGE_READER_MAX_IMAGE
            ).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            readerSurface = imageReader?.surface
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
            cameraCallback?.onCameraOpened(cameraId)
            cameraOpenCloseLock.release()
            this@CameraManipulator.cameraDevice = cameraDevice

            // Initialise the CaptureRequest.Builder for later use once the camera device is available
            previewRequestBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            // Auto focus should be continuous for camera preview.
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            setPreviewAndFrame()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            Log.i(TAG, "Camera disconnected: $cameraId")
            cameraOpenCloseLock.release()
            cameraDevice.close()
            cameraCallback?.onCameraClosed(cameraId)
            this@CameraManipulator.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            Log.e(TAG, "Camera state error code: $error")
            onDisconnected(cameraDevice)
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread = HandlerThread("CameraBackground#$THREAD_NUM++").also { it.start() }
            backgroundHandler = Handler(backgroundThread?.looper)
        }
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

    class Builder {
        internal var context: Context? = null
        internal var textureView: AutoFitTextureView? = null
        internal var flipOver = false
        internal var cameraId: String = "0"
        internal var cameraCallback: CameraCallback? = null
        internal var frameDataCallback: FrameDataCallback? = null
        internal var rotation: Int = 0
        /** Additional rotation is for calibrating some non-standard devices(Camera sensor isn't right).
         *  The Never-distorted cannot be guaranteed if rotate on a standard device */
        internal var additionalRotation = ROTATION_0
        internal var specificPreviewSize: Size? = null
        internal var frameSize: Size = DEFAULT_FRAME_SIZE
        internal var neverDistorted = true

        fun setClientContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setPreviewOn(textureView: AutoFitTextureView): Builder {
            this.textureView = textureView
            return this
        }

        fun setFlipOver(flipOver: Boolean): Builder {
            this.flipOver = flipOver
            return this
        }

        fun setCameraId(cameraId: Int): Builder {
            this.cameraId = cameraId.toString()
            return this
        }

        fun setCameraCallback(cameraCallback: CameraCallback): Builder {
            this.cameraCallback = cameraCallback
            return this
        }

        fun setFrameDataCallback(frameDataCallback: FrameDataCallback): Builder {
            this.frameDataCallback = frameDataCallback
            return this
        }

        fun setRotation(rotation: Int): Builder {
            this.rotation = rotation
            return this
        }

        fun setAdditionalRotation(additionalRotation: Int): Builder {
            this.additionalRotation = additionalRotation
            return this
        }

        fun setSpecificPreviewSize(specificPreviewSize: Size): Builder {
            this.specificPreviewSize = specificPreviewSize
            return this
        }

        fun setFrameSize(frameSize: Size): Builder {
            this.frameSize = frameSize
            return this
        }

        fun setNeverDistorted(neverDistorted: Boolean): Builder {
            this.neverDistorted = neverDistorted
            return this
        }

        fun build(): CameraManipulator? {
            if (context == null) {
                Log.e(TAG, "Client must pass its Android Context")
                return null
            }

            return CameraManipulator(this)
        }
    }

    companion object {
        private val TAG by lazy { CameraManipulator::class.java.simpleName }

        /**
         * Error message of opening the camera
         */
        const val PERMISSION_NOT_GRANTED = "Camera permission hasn't been granted"
        const val CAMERA_ID_INVALID = "Camera ID is invalid"
        const val CAMERA_CHARACTERISTIC_FAILED = "Failed to get camera characteristic"
        const val CAMERA_ACCESS_EXCEPTION = "Camera access exception"
        const val CAMERA_API_NOT_SUPPORTED = "Camera2 API is not supported on the device"
        const val CAMERA_CONFIG_FAILED = "Failed to configure camera preview"

        /** Max preview width that is guaranteed by Camera2 APi */
        private const val MAX_PREVIEW_WIDTH = 1920

        /** Max preview height that is guaranteed by Camera2 API */
        private const val MAX_PREVIEW_HEIGHT = 1080

        /** The upper and lower limits with regard to:
         * The camera preview area divides the TextureView area
         * Using a much higher preview resolution than the view's is a waste of resources.
         * Using too low resolution would have an unsatisfactory display effect
         */
        private const val UPPER_AREA_RATIO = 1.5F
        private const val LOWER_AREA_RATIO = 0.75F

        /** The sequence number of the background handler thread */
        private var THREAD_NUM = 0

        const val ROTATION_0 = 0
        const val ROTATION_90 = 90
        const val ROTATION_180 = 180
        const val ROTATION_270 = 270

        /** For the calculation of transformation.
         * Key is the screen rotation, value is the additional degree the image has to rotate to be upright */
        val rotationMap = mapOf(
            Surface.ROTATION_0 to ROTATION_0, Surface.ROTATION_90 to ROTATION_270,
            Surface.ROTATION_180 to ROTATION_180, Surface.ROTATION_270 to ROTATION_90
        )

        /** As per Android doc, it should be greater than 1 */
        const val IMAGE_READER_MAX_IMAGE = 2

        /** Default frame data size. If the camera doesn't support this output size, as per Android doc,
         * it will rounded to the most appropriate one */
        val DEFAULT_FRAME_SIZE = Size(320, 240)

        /**
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as
         * the respective max size, and whose aspect ratio matches with the specified value. If such
         * size doesn't exist, choose the largest one that is at most as large as the respective max
         * size, and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended
         *                          output class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @JvmStatic
        private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            additionalRotation: Int,
            specificPreviewSize: Size?
        ): Size {
            var bestSize: Size = choices[0]
            // Initialise the best size. The elements of choices usually sorted by resolution in descending order
            for (size in choices) {
                if (size.width <= MAX_PREVIEW_WIDTH && size.height <= MAX_PREVIEW_HEIGHT) {
                    bestSize = size
                    break
                }
            }

            var previewViewRatio = textureViewWidth / textureViewHeight.toFloat()
            previewViewRatio = if (previewViewRatio > 1) 1 / previewViewRatio else previewViewRatio

            val isNormalRotate = additionalRotation % 180 == 0

            val filterPreviewSize: (Size) -> Boolean = {
                if (it.width > MAX_PREVIEW_WIDTH || it.height > MAX_PREVIEW_HEIGHT) false
                else {
                    val result = (it.width * it.height) / (textureViewWidth * textureViewHeight).toFloat()
                    result in LOWER_AREA_RATIO..UPPER_AREA_RATIO
                }
            }

            for (size in choices) {
                // The client had specified its favorite preview size, just use it if it is supported
                if (specificPreviewSize != null && specificPreviewSize.width == size.width
                    && specificPreviewSize.height == size.height
                ) {
                    return size
                }

                if (isNormalRotate) {
                    if (filterPreviewSize(size) && (abs(size.height / size.width.toFloat() - previewViewRatio)
                                < abs(bestSize.height / bestSize.width.toFloat() - previewViewRatio))
                    ) {
                        bestSize = size
                    }
                } else {
                    if (filterPreviewSize(size) && (abs(size.width / size.height.toFloat() - previewViewRatio)
                                < abs(bestSize.width / bestSize.height.toFloat() - previewViewRatio))
                    ) {
                        bestSize = size
                    }
                }
            }

            return bestSize
        }
    }
}