package com.zbx.cameramodule

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.android.camera2basic.ConfirmationDialog
import com.example.android.camera2basic.ErrorDialog
import com.example.android.camera2basic.REQUEST_CAMERA_PERMISSION
import com.zbx.cameralib.AutoFitTextureView
import com.zbx.cameralib.CameraManipulator

class MainActivity : AppCompatActivity(), CameraManipulator.CameraCallback, View.OnClickListener,
    CameraManipulator.FrameDataCallback {
    private var cameraManipulator: CameraManipulator? = null
    private lateinit var textureView: AutoFitTextureView
    private lateinit var btnPreview: Button
    private lateinit var btnFrame: Button
    private lateinit var frameTextView: TextView

    private var previewStarted = false
    private var frameStarted = false
    private val handler = Handler()
    private var frameCount = 0

    override fun onCameraOpened(cameraId: String) {
    }

    override fun onCameraPreviewSize(cameraId: String, previewSize: Size) {
    }

    override fun onCameraClosed(cameraId: String) {
    }

    override fun onCameraError(cameraId: String, errorMsg: String) {
        if (errorMsg == CameraManipulator.PERMISSION_NOT_GRANTED) {
            requestCameraPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.view_preview)
        btnPreview = findViewById(R.id.button_preview)
        btnFrame = findViewById(R.id.button_frame)
        frameTextView = findViewById(R.id.textview_frame)

        btnPreview.setOnClickListener(this)
        btnFrame.setOnClickListener(this)
    }

    override fun onDataAvailable(frameData: ByteArray?) {
        frameData ?: return
        if (frameData?.isNotEmpty()) frameTextView.text = "Frame: ${++frameCount}"
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.button_preview -> {
                if (!previewStarted) {
                    cameraManipulator?.setPreviewOn(textureView)
                    btnPreview.text = resources.getText(R.string.stop_preview)
                    previewStarted = true
                } else {
                    cameraManipulator?.setPreviewOn(null)
                    btnPreview.text = resources.getText(R.string.start_preview)
                    previewStarted = false
                }
            }
            R.id.button_frame -> {
                if (!frameStarted) {
                    cameraManipulator?.setFrameDataCallback(this@MainActivity)
                    btnFrame.text = resources.getText(R.string.stop_frame)
                    frameStarted = true
                } else {
                    cameraManipulator?.setFrameDataCallback(null)
                    btnFrame.text = resources.getText(R.string.start_frame)
                    frameStarted = false
                    frameCount = 0
                }
            }
        }

        // Throttle the clicking or it may cause sorts of Camera Capture Session failure
        v?.isEnabled = false
        handler.postDelayed({
            btnFrame.isEnabled = true
            btnPreview.isEnabled = true
        }, THROTTLE_DELAY)
    }

    override fun onResume() {
        super.onResume()
        cameraManipulator =
            CameraManipulator.Builder().setClientContext(this)/*.setPreviewOn(textureView)*/.setCameraCallback(this)
                .setRotation(windowManager.defaultDisplay.rotation).setCameraId(1)
                /*.setAdditionalRotation(CameraManipulator.ROTATION_90).setFlipOver(true)*/.build()
        cameraManipulator?.start()

        // The camera had been stopped at onPause(), thus reset the states
        previewStarted = false
        frameStarted = false
        btnPreview.text = resources.getText(R.string.start_preview)
        btnFrame.text = resources.getText(R.string.start_frame)
    }

    override fun onPause() {
        super.onPause()
        cameraManipulator?.stop()
    }

    private fun requestCameraPermission() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
               ConfirmationDialog().show(supportFragmentManager, FRAGMENT_DIALOG)
           } else {
               requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
           }
       }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                    .show(supportFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        private const val FRAGMENT_DIALOG = "dialog"
        private const val THROTTLE_DELAY = 1000L
    }
}
