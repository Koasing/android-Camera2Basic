package com.example.android.camera2basic.opengl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20

class ColorShader(
        context: Context, texture: SurfaceTexture, width: Int, height: Int
): CameraRenderer(context, texture, width, height, "touchcolor.frag.glsl", "touchcolor.vert.glsl") {
    private var offsetR = 0.5f
    private var offsetG = 0.5f
    private var offsetB = 0.5f

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
}