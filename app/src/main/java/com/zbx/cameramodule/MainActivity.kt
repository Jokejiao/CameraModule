package com.zbx.cameramodule

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.zbx.cameralib.CameraManipulator

class MainActivity : AppCompatActivity() {
    var cameraManipulator: CameraManipulator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()
        cameraManipulator = CameraManipulator.Builder().setClientContext(this).build()
        cameraManipulator?.start()
    }

    override fun onPause() {
        super.onPause()
        cameraManipulator?.stop()
    }
}
