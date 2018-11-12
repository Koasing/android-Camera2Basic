// This codebase is originally from https://github.com/googlecreativelab/shadercam
// Interfaces and methods are updated for better readability
package com.example.android.camera2basic.opengl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.example.android.camera2basic.io.getStringFromFileInAssets
import com.example.android.camera2basic.services.Camera
import com.example.android.camera2basic.services.OnViewportSizeUpdatedListener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.*


/** *
 * Base camera rendering class. Responsible for rendering to proper window contexts.
 */
class CameraRenderer : Thread, SurfaceTexture.OnFrameAvailableListener {

    /**
     * if you create new files, just override these defaults in your subclass and
     * don't edit the [.vertexShaderCode] and [.fragmentShaderCode] variables
     */
    var DEFAULT_FRAGMENT_SHADER = "camera.frag.glsl"

    var DEFAULT_VERTEX_SHADER = "camera.vert.glsl"

    /**
     * Current context for use with utility methods
     */
    private lateinit var context: Context

    protected var surfaceWidth: Int = 0
    protected var surfaceHeight: Int = 0

    protected var surfaceAspectRatio: Float = 0.toFloat()

    /**
     * main texture for display, based on TextureView that is created in activity or fragment
     * and passed in after onSurfaceTextureAvailable is called, guaranteeing its existence.
     */
    private lateinit var surfaceTexture: SurfaceTexture

    /**
     * EGLCore used for creating [WindowSurface]s for preview and recording
     */
    private lateinit var eglCore: EglCore

    /**
     * Primary [WindowSurface] for rendering to screen
     */
    private lateinit var windowSurface: WindowSurface

    /**
     * Texture created for GLES rendering of camera data
     */
    var previewSurfaceTexture: SurfaceTexture? = null
        private set

    /**
     * if you override these in ctor of subclass, loader will ignore the files listed above
     */
    protected var vertexShaderCode: String? = null

    protected var fragmentShaderCode: String? = null

    private var textureBuffer: FloatBuffer? = null

    private val textureCoords = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)

    private var cameraShaderProgram: Int = 0

    private var vertexBuffer: FloatBuffer? = null

    private var drawListBuffer: ShortBuffer? = null

    private var textureCoordinateHandle: Int = 0

    private var positionHandle: Int = 0

    /**
     * for storing all texture ids from genTextures, and used when binding
     * after genTextures, id[0] is reserved for camera texture
     */
    private val texturesIds = IntArray(MAX_TEXTURES)

    /**
     * array of proper constants for use in creation,
     * updating, and drawing. most phones max out at 16
     * same number as [.MAX_TEXTURES]
     *
     * Used in our implementation of [.addTexture]
     */
    private val textureConsts = intArrayOf(
            GLES20.GL_TEXTURE1,
            GLES20.GL_TEXTURE2,
            GLES20.GL_TEXTURE3,
            GLES20.GL_TEXTURE4,
            GLES20.GL_TEXTURE5,
            GLES20.GL_TEXTURE6,
            GLES20.GL_TEXTURE7,
            GLES20.GL_TEXTURE8,
            GLES20.GL_TEXTURE9,
            GLES20.GL_TEXTURE10,
            GLES20.GL_TEXTURE11,
            GLES20.GL_TEXTURE12,
            GLES20.GL_TEXTURE13,
            GLES20.GL_TEXTURE14,
            GLES20.GL_TEXTURE15,
            GLES20.GL_TEXTURE16)

    /**
     * array of [Texture] objects used for looping through
     * during the render pass. created in [.addTexture]
     * and looped in [.setExtraTextures]
     */
    private var textureArray: ArrayList<Texture>? = null

    /**
     * matrix for transforming our camera texture, available immediately after [.mPreviewTexture]s
     * `updateTexImage()` is called in our main [.draw] loop.
     */
    private val cameraTransformMatrix = FloatArray(16)

    /**
     * Handler for communcation with the UI thread. Implementation below at
     * [RenderHandler][com.androidexperiments.shadercam.gl.CameraRenderer.RenderHandler]
     */
    private var renderHandler: RenderHandler? = null
        private set

    /**
     * Interface listener for some callbacks to the UI thread when rendering is setup and finished.
     */
    private var onRendererReadyListener: OnRendererReadyListener? = null

    /**
     * Width and height storage of our viewport size, so we can properly accomodate any size View
     * used to display our preview on screen.
     */
    private var mViewportWidth: Int = 0
    private var mViewportHeight: Int = 0

    /**
     * Reference to Camera class
     */
    var camera: Camera? = null

    private lateinit var fragmentShaderPath: String
    private lateinit var vertexShaderPath: String

    /**
     * Simple ctor to use default shaders
     */
    constructor(context: Context, texture: SurfaceTexture, width: Int, height: Int) {
        init(context, texture, width, height, DEFAULT_FRAGMENT_SHADER, DEFAULT_VERTEX_SHADER)
    }

    /**
     * Main constructor for passing in shaders to override the default shader.
     * Context, texture, width, and height are passed in automatically by CameraTextureListener
     * @param fragPath the file name of your fragment shader, ex: "lip_service.frag" if it is top-level /assets/ folder. Add subdirectories if needed
     * @param vertPath the file name of your vertex shader, ex: "lip_service.vert" if it is top-level /assets/ folder. Add subdirectories if needed
     */
    constructor(context: Context, texture: SurfaceTexture, width: Int, height: Int, fragPath: String, vertPath: String) {
        init(context, texture, width, height, fragPath, vertPath)
    }

    private fun init(context: Context, texture: SurfaceTexture, width: Int, height: Int, fragPath: String, vertPath: String) {
        this.name = THREAD_NAME

        this.context = context
        this.surfaceTexture = texture

        this.surfaceWidth = width
        this.surfaceHeight = height
        this.surfaceAspectRatio = width.toFloat() / height

        this.fragmentShaderPath = fragPath
        this.vertexShaderPath = vertPath
    }

    private fun initialize() {
        textureArray = ArrayList()

        setupCameraView()
        setViewport(surfaceWidth, surfaceHeight)

        if (fragmentShaderCode == null || vertexShaderCode == null) {
            loadFromShadersFromAssets(fragmentShaderPath, vertexShaderPath)
        }
    }

    private fun setupCameraView() {
        if (camera == null) {
            throw RuntimeException("camera is null! " +
                    "Please set Camera prior to initialization.")
        }

        camera?.viewPortSizeListener = object : OnViewportSizeUpdatedListener {
            override fun onViewportSizeUpdated(viewportWidth: Int, viewportHeight: Int) {
                mViewportWidth = viewportWidth
                mViewportHeight = viewportHeight
            }
        }
    }

    private fun loadFromShadersFromAssets(pathToFragment: String, pathToVertex: String) {
        try {
            fragmentShaderCode = getStringFromFileInAssets(context, pathToFragment)
            vertexShaderCode = getStringFromFileInAssets(context, pathToVertex)
        } catch (e: IOException) {
            Log.e(TAG, "loadFromShadersFromAssets() failed. Check paths to assets.\n" + e.message)
        }

    }

    /**
     * Initialize all necessary components for GLES rendering, creating window surfaces for drawing
     * the preview as well as the surface that will be used by MediaRecorder for recording
     */
    fun initGL() {
        eglCore = EglCore(null, EglCore.FLAG_RECORDABLE or EglCore.FLAG_TRY_GLES3)

        //create preview surface
        windowSurface = WindowSurface(eglCore, surfaceTexture)
        windowSurface.makeCurrent()

        initGLComponents()
    }

    protected fun initGLComponents() {
        onPreSetupGLComponents()

        setupVertexBuffer()
        setupTextures()
        setupCameraTexture()
        setupShaders()

        onSetupComplete()
    }


    // ------------------------------------------------------------
    // deinit
    // ------------------------------------------------------------

    fun deinitGL() {
        deinitGLComponents()

        windowSurface.release()
        eglCore.release()
    }

    protected fun deinitGLComponents() {
        GLES20.glDeleteTextures(MAX_TEXTURES, texturesIds, 0)
        GLES20.glDeleteProgram(cameraShaderProgram)

        previewSurfaceTexture?.release()
        previewSurfaceTexture?.setOnFrameAvailableListener(null)
    }

    // ------------------------------------------------------------
    // setup
    // ------------------------------------------------------------

    /**
     * override this method if there's anything else u want to accomplish before
     * the main camera setup gets underway
     */
    private fun onPreSetupGLComponents() {

    }

    protected fun setupVertexBuffer() {
        // Draw list buffer
        val dlb = ByteBuffer.allocateDirect(drawOrder.size * 2)
        dlb.order(ByteOrder.nativeOrder())
        drawListBuffer = dlb.asShortBuffer()
        drawListBuffer?.put(drawOrder)
        drawListBuffer?.position(0)

        // Initialize the texture holder
        val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer?.put(squareCoords)
        vertexBuffer?.position(0)
    }

    protected fun setupTextures() {
        val texturebb = ByteBuffer.allocateDirect(textureCoords.size * 4)
        texturebb.order(ByteOrder.nativeOrder())

        textureBuffer = texturebb.asFloatBuffer()
        textureBuffer?.put(textureCoords)
        textureBuffer?.position(0)

        // Generate the max amount texture ids
        GLES20.glGenTextures(MAX_TEXTURES, texturesIds, 0)
        checkGlError("Texture generate")
    }

    /**
     * Remember that Android's camera api returns camera texture not as [GLES20.GL_TEXTURE_2D]
     * but rather as [GLES11Ext.GL_TEXTURE_EXTERNAL_OES], which we bind here
     */
    protected fun setupCameraTexture() {
        //set texture[0] to camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texturesIds[0])
        checkGlError("Texture bind")

        previewSurfaceTexture = SurfaceTexture(texturesIds[0])
        previewSurfaceTexture?.setOnFrameAvailableListener(this)
    }

    /**
     * Handling this manually here but check out another impl at [GlUtil.createProgram]
     */
    protected fun setupShaders() {
        val vertexShaderHandle = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vertexShaderHandle, vertexShaderCode)
        GLES20.glCompileShader(vertexShaderHandle)
        checkGlError("Vertex shader compile")

        Log.d(TAG, "vertexShader info log:\n " + GLES20.glGetShaderInfoLog(vertexShaderHandle))

        val fragmentShaderHandle = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fragmentShaderHandle, fragmentShaderCode)
        GLES20.glCompileShader(fragmentShaderHandle)
        checkGlError("Pixel shader compile")

        Log.d(TAG, "fragmentShader info log:\n " + GLES20.glGetShaderInfoLog(fragmentShaderHandle))

        cameraShaderProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(cameraShaderProgram, vertexShaderHandle)
        GLES20.glAttachShader(cameraShaderProgram, fragmentShaderHandle)
        GLES20.glLinkProgram(cameraShaderProgram)
        checkGlError("Shader program compile")

        val status = IntArray(1)
        GLES20.glGetProgramiv(cameraShaderProgram, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(cameraShaderProgram)
            Log.e("SurfaceTest", "Error while linking program:\n$error")
        }
    }

    /**
     * called when all setup is complete on basic GL stuffs
     * override for adding textures and other shaders and make sure to call
     * super so that we can let them know we're done
     */
    protected fun onSetupComplete() {
        onRendererReadyListener?.onRendererReady()
    }

    @Synchronized
    override fun start() {
        initialize()

        if (onRendererReadyListener == null)
            throw RuntimeException("OnRenderReadyListener is not set! Set listener prior to calling start()")

        super.start()
    }


    /**
     * primary loop - this does all the good things
     */
    override fun run() {
        Looper.prepare()
        //create handler for communication from UI
        renderHandler = RenderHandler(this)

        //initialize all GL on this context
        initGL()

        //LOOOOOOOOOOOOOOOOP
        Looper.loop()

        //we're done here
        deinitGL()

        onRendererReadyListener?.onRendererFinished()
    }

    /**
     * stop our thread, and make sure we kill a recording if its still happening
     *
     * this should only be called from our handler to ensure thread-safe
     */
    fun shutdown() {
        //kill ouy thread
        Looper.myLooper()?.quit()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        var swapResult: Boolean

        synchronized(this) {
            updatePreviewTexture()

            if (eglCore.glVersion >= 3) {
                draw()

                //swap main buff
                windowSurface.makeCurrent()
                swapResult = windowSurface.swapBuffers()
            } else
            //gl v2
            {
                draw()
                windowSurface.makeCurrent()
                swapResult = windowSurface.swapBuffers()
            }

            if (!swapResult) {
                // This can happen if the Activity stops without waiting for us to halt.
                Log.e(TAG, "swapBuffers failed, killing renderer thread")
                shutdown()
            }
        }
    }

    /**
     * main draw routine
     */
    fun draw() {
        GLES20.glViewport(0, 0, mViewportWidth, mViewportHeight)

        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        //set shader
        GLES20.glUseProgram(cameraShaderProgram)

        setUniformsAndAttribs()
        setExtraTextures()
        drawElements()
        onDrawCleanup()
    }

    /**
     * update the SurfaceTexture to the latest camera image
     */
    protected fun updatePreviewTexture() {
        previewSurfaceTexture?.updateTexImage()
        previewSurfaceTexture?.getTransformMatrix(cameraTransformMatrix)
    }

    /**
     * base amount of attributes needed for rendering camera to screen
     */
    protected fun setUniformsAndAttribs() {
        val textureParamHandle = GLES20.glGetUniformLocation(cameraShaderProgram, "camTexture")
        val textureTranformHandle = GLES20.glGetUniformLocation(cameraShaderProgram, "camTextureTransform")
        textureCoordinateHandle = GLES20.glGetAttribLocation(cameraShaderProgram, "camTexCoordinate")
        positionHandle = GLES20.glGetAttribLocation(cameraShaderProgram, "position")


        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 4 * 2, vertexBuffer)

        //camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texturesIds[0])
        GLES20.glUniform1i(textureParamHandle, 0)

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 2, GLES20.GL_FLOAT, false, 4 * 2, textureBuffer)

        GLES20.glUniformMatrix4fv(textureTranformHandle, 1, false, cameraTransformMatrix, 0)
    }

    /**
     * creates a new texture with specified resource id and returns the
     * tex id num upon completion
     * @param resource_id
     * @param uniformName
     * @return
     */
    fun addTexture(resource_id: Int, uniformName: String): Int {
        val texId = textureConsts[textureArray?.size ?: 0]
        if (textureArray?.size?.plus(1) ?: 0 >= MAX_TEXTURES)
            throw IllegalStateException("Too many textures! Please don't use so many :(")

        val bmp = BitmapFactory.decodeResource(context.resources, resource_id)

        return addTexture(texId, bmp, uniformName, true)
    }

    fun addTexture(bitmap: Bitmap, uniformName: String): Int {
        val texId = textureConsts[textureArray?.size ?: 0]
        if (textureArray?.size?.plus(1) ?: 0 >= MAX_TEXTURES)
            throw IllegalStateException("Too many textures! Please don't use so many :(")

        return addTexture(texId, bitmap, uniformName, true)
    }

    fun addTexture(texId: Int, bitmap: Bitmap, uniformName: String, recycle: Boolean): Int {
        val num = textureArray?.size?.plus(1) ?: 0

        GLES20.glActiveTexture(texId)
        checkGlError("Texture generate")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturesIds[num])
        checkGlError("Texture bind")
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST.toFloat())
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        if (recycle)
            bitmap.recycle()

        val tex = Texture(num, texId, uniformName)
        textureArray?.let {
            if(!it.contains(tex)) {
                it.add(tex)
            }
        }

        return num
    }

    /**
     * updates specific texture and recycles bitmap used for updating
     * @param texNum
     * @param drawingCache
     */
    fun updateTexture(texNum: Int, drawingCache: Bitmap) {
        GLES20.glActiveTexture(textureConsts[texNum - 1])
        checkGlError("Texture generate")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturesIds[texNum])
        checkGlError("Texture bind")
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, drawingCache)
        checkGlError("Tex Sub Image")

        drawingCache.recycle()
    }

    /**
     * override this and copy if u want to add your own texturesIds
     * if u need different uv coordinates, refer to [.setupTextures]
     * for how to create your own buffer
     */
    protected fun setExtraTextures() {
        textureArray?.forEach {
            val imageParamHandle = GLES20.glGetUniformLocation(cameraShaderProgram, it.uniformName)

            GLES20.glActiveTexture(it.texId)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturesIds[it.texNum])
            GLES20.glUniform1i(imageParamHandle, it.texNum)
        }
    }

    protected fun drawElements() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.size, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)
    }

    protected fun onDrawCleanup() {
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle)
    }

    /**
     * utility for checking GL errors
     * @param op
     */
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if(error != GLES20.GL_NO_ERROR) {
            Log.e("SurfaceTest", op + ": glError " + GLUtils.getEGLErrorString(error))
        }
    }

    //getters and setters

    fun setViewport(viewportWidth: Int, viewportHeight: Int) {
        mViewportWidth = viewportWidth
        mViewportHeight = viewportHeight
    }

    fun setOnRendererReadyListener(listener: OnRendererReadyListener) {
        onRendererReadyListener = listener

    }

    /**
     * Copies file recorded to our temp file into the user-defined file upon completion
     */
    @Throws(IOException::class)
    private fun copyFile(src: File?, dst: File?) {
        val inChannel = FileInputStream(src).channel
        val outChannel = FileOutputStream(dst).channel

        try {
            inChannel?.transferTo(0, inChannel.size(), outChannel)
        } finally {
            inChannel?.close()

            outChannel?.close()
        }
    }

    /**
     * Internal class for storing refs to texturesIds for rendering
     */
    inner class Texture (var texNum: Int, var texId: Int, var uniformName: String) {
        override fun toString(): String {
            return "[Texture] num: $texNum id: $texId, uniformName: $uniformName"
        }
    }

    /**
     * [Handler] responsible for communication between this render thread and the UI thread.
     *
     * For now, the only thing we really need to worry about is shutting down the thread upon completion
     * of recording, since we cannot access the [android.media.MediaRecorder] surface once
     * [MediaRecorder.stop] is called.
     */
    class RenderHandler(rt: CameraRenderer) : Handler() {

        /**
         * Our camera renderer ref, weak since we're dealing with static class so it doesn't leak
         */
        private val mWeakRenderer: WeakReference<CameraRenderer> = WeakReference(rt)

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * Call from UI thread.
         */
        fun sendShutdown() {
            sendMessage(obtainMessage(MSG_SHUTDOWN))
        }

        override fun handleMessage(msg: Message) {
            val renderer = mWeakRenderer.get()
            if (renderer == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null")
                return
            }

            val what = msg.what
            when (what) {
                MSG_SHUTDOWN -> renderer.shutdown()
                else -> throw RuntimeException("unknown message $what")
            }
        }

        companion object {
            private val TAG = RenderHandler::class.java.simpleName

            private val MSG_SHUTDOWN = 0
        }
    }

    /**
     * Interface for callbacks when render thread completes its setup
     */
    interface OnRendererReadyListener {
        /**
         * Called when [.onSetupComplete] is finished with its routine
         */
        fun onRendererReady()

        /**
         * Called once the looper is killed and our [.run] method completes
         */
        fun onRendererFinished()
    }

    companion object {
        private val TAG = CameraRenderer::class.java.simpleName
        private val THREAD_NAME = "CameraRendererThread"

        /**
         * Basic mesh rendering code
         */
        private val squareSize = 1.0f

        private val squareCoords = floatArrayOf(
                -squareSize, squareSize,   // 0.0f,   // top left
                squareSize, squareSize,    // 0.0f,   // top right
                -squareSize, -squareSize,  // 0.0f,   // bottom left
                squareSize, -squareSize)   // 0.0f,   // bottom right

        private val drawOrder = shortArrayOf(0, 1, 2, 1, 3, 2)

        /**
         * "arbitrary" maximum number of textures. seems that most phones don't like more than 16
         */
        val MAX_TEXTURES = 16
    }
}