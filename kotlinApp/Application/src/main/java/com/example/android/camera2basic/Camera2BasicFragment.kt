/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2basic

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.*
import com.example.android.camera2basic.opengl.CameraRenderer
import com.example.android.camera2basic.opengl.ColorShader
import com.example.android.camera2basic.services.Camera
import com.example.android.camera2basic.services.ImageHandler
import com.example.android.camera2basic.services.ImageSaver
import com.example.android.camera2basic.ui.AutoFitTextureView
import com.example.android.camera2basic.ui.ConfirmationDialog
import com.example.android.camera2basic.ui.ErrorDialog
import java.io.File

enum class CameraMode {
    AUTO_FIT, FULL_SCREEN, OPENGL
}

class Camera2BasicFragment : Fragment(), View.OnClickListener,
        ActivityCompat.OnRequestPermissionsResultCallback {
    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if(isOpenGLMode) {
                // set Renderer
                initRenderer(texture, width, height)
            } else {
                openCamera(width, height)
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    }

    private val onRendererReadyListener = object : CameraRenderer.OnRendererReadyListener {
        override fun onRendererReady() {
            activity?.runOnUiThread {
                previewSurface = colorShader?.previewSurfaceTexture
                openCameraForOpenGL(textureView.width, textureView.height)
            }
        }

        override fun onRendererFinished() {
        }

    }

    /**
     * An [AutoFitTextureView] for camera preview.
     */
    private lateinit var textureView: AutoFitTextureView

    /**
     * SurfaceView to render camera preview
     */
    private var previewSurface: SurfaceTexture? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private lateinit var previewSize: Size

    /**
     * This is the output file for our picture.
     */
    private lateinit var file: File

    /**
     * Whether the current camera device supports Flash or not.
     */
    private var flashSupported = false

    /**
     * Camera module
     */
    private var camera: Camera? = null

    /**
     * Camera Renderer for OpenGL
     */
    private var colorShader: ColorShader? = null

    private val cameraMode: CameraMode = CameraMode.FULL_SCREEN
    private val isOpenGLMode
      get() = cameraMode == CameraMode.OPENGL



    override fun onCreateView(inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera2_basic, container, false)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.picture).setOnClickListener(this)
        view.findViewById<View>(R.id.info).setOnClickListener(this)
        textureView = view.findViewById(R.id.texture)

        if(isOpenGLMode) {
            // touch and update filter
            textureView.setOnTouchListener { v, event ->
                colorShader?.setTouchPoint(event.rawX, event.rawY)

                return@setOnTouchListener true
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        file = File(activity?.getExternalFilesDir(null), PIC_FILE_NAME)
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camera = Camera.initInstance(manager)
    }

    override fun onResume() {
        super.onResume()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable) {
            if(isOpenGLMode) {
                openCameraForOpenGL(textureView.width, textureView.height)
            } else {
                openCamera(textureView.width, textureView.height)
            }
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        camera?.close()
        super.onPause()
    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int, camera: Camera, mode: CameraMode) {
        try {
            // val largest = camera.getCaptureSize()
            // For preview, we want to make sure camera fits to screen size

            val largest = when(mode) {
                CameraMode.AUTO_FIT -> {
                    // we want to make sure captured image fits to screen size,
                    // so choose the largest one we can get from supported capture sizes
                    camera.getCaptureSize()
                }
                CameraMode.OPENGL, CameraMode.FULL_SCREEN -> {
                    // In this example, opengl is also full screen.
                    // When full screen, we choose the largest from supported surface view sizes
                    val realSize = Point()
                    activity?.windowManager?.defaultDisplay?.getRealSize(realSize)
                    val aspectRatio = realSize.x.toFloat()/ realSize.y.toFloat()
                    camera.getPreviewSize(aspectRatio)
                }
            }

            // Find out if we need to swap dimension to get the preview size relative to sensor
            // coordinate.
            val displayRotation = activity?.windowManager?.defaultDisplay?.rotation ?: return

            val sensorOrientation = camera.getSensorOrientation()

            val swappedDimensions = areDimensionsSwapped(sensorOrientation, displayRotation)

            val displaySize = Point()
            activity?.windowManager?.defaultDisplay?.getSize(displaySize)

            Log.d(TAG, "===== display size ${displaySize.x} ${displaySize.y} ")
            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            if (swappedDimensions) {
                previewSize = camera.chooseOptimalSize(
                    height,
                    width,
                    displaySize.y,
                    displaySize.x,
                    largest)
            } else {
                previewSize = camera.chooseOptimalSize(
                    width,
                    height,
                    displaySize.x,
                    displaySize.y,
                    largest)
            }

            if(mode == CameraMode.AUTO_FIT) {
                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize.width, previewSize.height)
                } else {
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                }
            }
            // Check if the flash is supported.
            flashSupported = camera.getFlashSupported()

        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        }

    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param sensorOrientation The current sensor orientation
     * @param displayRotation The current rotation of the display
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(sensorOrientation: Int, displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    /**
     * Opens the camera specified by [Camera2BasicFragment.cameraId].
     */
    private fun openCamera(width: Int, height: Int) {
        if (activity == null) {
            Log.e(TAG, "activity is not ready!")
            return
        }
        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                setUpCameraOutputs(width, height, it, cameraMode)
                configureTransform(width, height)
                it.open()
                val texture = textureView.surfaceTexture
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(Surface(texture))
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Opens the camera for OpenGL. The main difference is that OpenGL requires Preview Surface
     * For rendering Camera images.
     */
    private fun openCameraForOpenGL(width: Int, height: Int) {
        if (activity == null) {
            Log.e(TAG, "activity is not ready!")
            return
        }
        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                setUpCameraOutputs(width, height, it, cameraMode)
                configureTransform(width, height)
                colorShader?.setViewport(width, height)
                it.open()
                previewSurface?.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(Surface(previewSurface))
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.picture -> camera?.takePicture(object : ImageHandler {
                override fun handleImage(image: Image): Runnable {
                    return ImageSaver(image, file)
                }
            })
            R.id.info -> {
                if (activity != null) {
                    AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                }
            }
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (flashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }

    private fun initRenderer(texture: SurfaceTexture, width: Int, height: Int) {
        colorShader = getRenderer(texture, width, height)
        colorShader?.camera = camera
        colorShader?.setOnRendererReadyListener(onRendererReadyListener)
        colorShader?.start()
    }

    private fun getRenderer(texture: SurfaceTexture, width: Int, height: Int) =
            ColorShader(
                    context!!,
                    texture,
                    width,
                    height)

    companion object {

        private const val FRAGMENT_DIALOG = "dialog"
        /**
         * Tag for the [Log].
         */
        private const val TAG = "Camera2BasicFragment"

        @JvmStatic fun newInstance(): Camera2BasicFragment = Camera2BasicFragment()
    }
}
