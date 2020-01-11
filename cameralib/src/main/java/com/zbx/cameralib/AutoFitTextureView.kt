package com.zbx.cameralib

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView

/**
 * Author: Ke Jiao (Alex)
 * A [TextureView] that can be adjusted to a specified aspect ratio.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {
    private val TAG by lazy { AutoFitTextureView::class.java.simpleName }
    private var ratioWidth = 0
    private var ratioHeight = 0

    private var transformRoutine: ((Int, Int) -> Unit)? = null

    /**
     * In order for displaying undistorted camera image as well as showing the exact central region of the camera image.
     * An additional matrix X/Y translation might be needed after the re-layout.
     */
    private var xTranslation: Int = 0
    private var yTranslation: Int = 0

    fun getXTranslation() = xTranslation
    fun getYTranslation() = yTranslation

    fun setTransformRoutine(routine: ((Int, Int) -> Unit)?) {
        this.transformRoutine = routine
    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    /** Whether the width or height should be adjusted by the lib. In most cases, the textureView dimension
     *  need to be changed unless its aspect radio equals to a specific camera preview size
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val modeWidth = MeasureSpec.getMode(widthMeasureSpec)
        val modeHeight = MeasureSpec.getMode(heightMeasureSpec)

        Log.d(TAG, "Measure width mode: $modeWidth, width: $width")
        Log.d(TAG, "Measure height mode: $modeHeight, height: $height")

        /** Reset translations */
        xTranslation = 0
        yTranslation = 0

        /**
         * Here is a trick. On the first time of measuring the view, its width/height will
         * be set to the "AT_MOST" lengths given by parent(If the spec modes are AT_MOST).
         * So if you inspect the layout in layout editor, you may find the view is fully stretched
         */
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
            return
        }

        /** Don't change the dimension, only calculate X/Y translations */
        if (modeWidth == MeasureSpec.EXACTLY && modeHeight == MeasureSpec.EXACTLY) {
            /**
             * Yes, the dimension would be left unchanged. But suppose the view is going to be stretched
             * along single side to match the preview aspect ratio, then it is certain that the frame won't be
             * distorted as rendering on the new rect. In this way, calling matrix setRectToRect() and then
             * translate half of the extended length along the axis(inversely) can make the view displays
             * the central region of the camera image
             * xTranslation and yTranslation are invariably minus or zero in this logic
             */
            val refWidth = height * ratioWidth / ratioHeight
            if (width < refWidth) {
                val refHeight = width * ratioHeight / ratioWidth
                yTranslation = refHeight - height
            } else {
                xTranslation = refWidth - width
            }

            // The onSurfaceTextureSizeChanged() won't be called since the dimension isn't changed
            setMeasuredDimension(width, height)
            transformRoutine?.invoke(width, height)
            return
        }

        /** The transformation will take place at onSurfaceTextureSizeChanged */
        if ((width < height * ratioWidth / ratioHeight || modeWidth == MeasureSpec.EXACTLY)
            && modeHeight != MeasureSpec.EXACTLY
        ) {
            var newHeight = width * ratioHeight / ratioWidth  // Height is invariably greater than newHeight
            if (modeHeight == MeasureSpec.AT_MOST && newHeight > height) {
                yTranslation = newHeight - height
                newHeight = height
            }
            setMeasuredDimension(width, newHeight)
        } else {
            var newWidth = height * ratioWidth / ratioHeight
            if (modeWidth == MeasureSpec.AT_MOST && newWidth > width) {
                xTranslation = newWidth - width
                newWidth = width
            }
            setMeasuredDimension(newWidth, height)
        }
    }
}
