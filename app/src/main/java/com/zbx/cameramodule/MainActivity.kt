package com.zbx.cameramodule

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Size
import android.widget.Button
import com.example.android.camera2basic.ConfirmationDialog
import com.example.android.camera2basic.ErrorDialog
import com.example.android.camera2basic.REQUEST_CAMERA_PERMISSION
import com.zbx.cameralib.AutoFitTextureView
import com.zbx.cameralib.CameraManipulator

class MainActivity : AppCompatActivity(), CameraManipulator.CameraCallback {
    private var cameraManipulator: CameraManipulator? = null
    private lateinit var textureView: AutoFitTextureView

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
    }

    override fun onResume() {
        super.onResume()
        cameraManipulator =
            CameraManipulator.Builder().setClientContext(this).setPreviewOn(textureView).setCameraCallback(this)
                .setRotation(windowManager.defaultDisplay.rotation).setCameraId(1)
                .setAdditionalRotation(CameraManipulator.ROTATION_90).setFlipOver(true).build()
        cameraManipulator?.start()
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
    }
}
