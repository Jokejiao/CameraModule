# CameraModule
Android Camera2 Library

An instance of CameraManipulator enables the client (the app uses this lib) to open a specific camera,
render the preview frame on a TextureView, and obtain frame data

Simply start camera preview and fetch frame data by:

 cameraManipulator = 
            CameraManipulator.Builder()
                .setClientContext(this).setCameraCallback(this)
                .setRotation(windowManager.defaultDisplay.rotation).setCameraId(1)
                    .setPreviewOn(textureView).setFrameDataCallback(this@MainActivity).build()
 
 cameraManipulator?.start()


Highlight capabilities:

(1) Automatically choose optimal preview size based on the camera and the dimension of the TextureView

(2) Reversely adjust the dimension of the TextureView based on its layout constraints

(3) Supporting Never-Distort by showing the exact central region of the image

(4) Independently Start/Stop preview/frame catching at any time at will

(5) Supporting screen rotations (If the gravity sensor does work)

(6) Additional image rotation(For calibrating non-standard device) and flip-over horizontally(Mirror effect)
