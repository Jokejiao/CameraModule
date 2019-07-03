package com.zbx.cameramodule

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.zbx.cameralib.CameraManipulator

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var cameraManipulator = CameraManipulator(this, "0")
        cameraManipulator.start()
    }
}
