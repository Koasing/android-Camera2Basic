package com.example.android.camera2basic.opengl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.absoluteValue

class ColorShader(
        context: Context, texture: SurfaceTexture, width: Int, height: Int
): CameraRenderer(context, texture, width, height, "touchcolor.frag.glsl", "touchcolor.vert.glsl") {
    private var offsetR = 1.0f
    private var offsetG = 1.0f
    private var offsetB = 1.0f

    /**
     * we override [.setUniformsAndAttribs] and make sure to call the super so we can add
     * our own uniforms to our shaders here. CameraRenderer handles the rest for us automatically
     */
    override fun setUniformsAndAttribs() {
        super.setUniformsAndAttribs()

        val offsetRLoc = GLES20.glGetUniformLocation(cameraShaderProgram, "offsetR")
        val offsetGLoc = GLES20.glGetUniformLocation(cameraShaderProgram, "offsetG")
        val offsetBLoc = GLES20.glGetUniformLocation(cameraShaderProgram, "offsetB")

        GLES20.glUniform1f(offsetRLoc, offsetR)
        GLES20.glUniform1f(offsetGLoc, offsetG)
        GLES20.glUniform1f(offsetBLoc, offsetB)
    }

    /**
     * take touch points on that textureview and turn them into multipliers for the color channels
     * of our shader, simple, yet effective way to illustrate how easy it is to integrate app
     * interaction into our glsl shaders
     * @param rawX raw x on screen
     * @param rawY raw y on screen
     */
    fun setTouchPoint(rawX: Float, rawY: Float) {
        offsetR = rawX / surfaceWidth
        offsetG = rawY / surfaceHeight
        offsetB = offsetR / offsetG
    }

    private var valueRed: Float = 0f
    private var valueGreen: Float = 0f
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var isRunning: Boolean = false
    fun randomlyChangeColor() {
        thread = HandlerThread("shader").also {
            it.start()
            isRunning = true
            handler = Handler(it.looper).apply {
                post {
                    while(isRunning) {
                        offsetR = ((surfaceWidth - valueRed).absoluteValue % surfaceWidth) / surfaceWidth
                        offsetG = ((surfaceHeight - valueGreen).absoluteValue % surfaceHeight) / surfaceHeight
                        offsetB = offsetR / offsetG
                        valueGreen += 100
                        valueRed += 100
                        sleep(100)
                    }
                }
            }
        }

    }

    fun stopRandomColorChange() {
        isRunning = false
        thread?.quit()
        thread = null
        handler = null
        offsetR = 1.0f
        offsetG = 1.0f
        offsetB = 1.0f
        valueGreen = 0f
        valueRed = 0f
    }
}