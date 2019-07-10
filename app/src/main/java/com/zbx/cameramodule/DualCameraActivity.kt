package com.zbx.cameramodule

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.zbx.cameralib.AutoFitTextureView
import com.zbx.cameralib.CameraManipulator

class DualCameraActivity : AppCompatActivity() {
    private var cameraManipulatorRgb: CameraManipulator? = null
    private var cameraManipulatorIr: CameraManipulator? = null
    private lateinit var textureViewRgb: AutoFitTextureView
    private lateinit var textureViewIr: AutoFitTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_camera)

        textureViewRgb = findViewById(R.id.textureview_rgb)
        textureViewIr = findViewById(R.id.textureview_ir)
    }

    override fun onResume() {
        super.onResume()

        cameraManipulatorRgb = CameraManipulator.Builder().setClientContext(this).setPreviewOn(textureViewRgb)
            .setRotation(windowManager.defaultDisplay.rotation).setCameraId(1).setAdditionalRotation(
                CameraManipulator.ROTATION_90
            ).setFlipOver(true).build()
        cameraManipulatorRgb?.start()

        cameraManipulatorIr = CameraManipulator.Builder().setClientContext(this).setPreviewOn(textureViewIr)
            .setRotation(windowManager.defaultDisplay.rotation).setAdditionalRotation(CameraManipulator.ROTATION_270)
            .setCameraId(0).build()
        cameraManipulatorIr?.start()
    }

    override fun onPause() {
        super.onPause()
        cameraManipulatorRgb?.stop()
        cameraManipulatorIr?.stop()
    }
}
