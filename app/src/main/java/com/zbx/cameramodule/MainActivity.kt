package com.zbx.cameramodule

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import com.zbx.cameralib.AutoFitTextureView
import com.zbx.cameralib.CameraManipulator

class MainActivity : AppCompatActivity() {
    private var cameraManipulator: CameraManipulator? = null
    private lateinit var textureView: AutoFitTextureView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.view_preview)
        findViewById<Button>(R.id.button).setOnClickListener { cameraManipulator?.setPreviewOn(textureView) }
    }

    override fun onResume() {
        super.onResume()
//        cameraManipulator = CameraManipulator.Builder().setClientContext(this).build()
        cameraManipulator = CameraManipulator.Builder().setClientContext(this).setPreviewOn(textureView).setCameraId(1).build()
        cameraManipulator?.start()
    }

    override fun onPause() {
        super.onPause()
        cameraManipulator?.stop()
    }
}
