package com.example.arcoredemo

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.arcoredemo.ui.theme.ARCoreDemoTheme
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {
    
    private var arSession: Session? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var surfaceCreated = false
    private var installRequested = false
    private var backgroundRenderer: BackgroundRenderer? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupARSession()
        } else {
            Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ARCoreDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ARCoreView()
                }
            }
        }
    }

    @Composable
    private fun ARCoreView() {
        val lifecycleOwner = LocalLifecycleOwner.current
        
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> onResume()
                    Lifecycle.Event.ON_PAUSE -> onPause()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        AndroidView(
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(ArRenderer())
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glSurfaceView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    private fun checkCameraPermissionAndSetup() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                setupARSession()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupARSession() {
        if (arSession == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        // ARCore is installed
                    }
                }

                arSession = Session(this).apply {
                    val config = Config(this)
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    configure(config)
                }
                
                Toast.makeText(this, "ARCore session created successfully", Toast.LENGTH_SHORT).show()
            } catch (e: UnavailableArcoreNotInstalledException) {
                Toast.makeText(this, "ARCore not installed", Toast.LENGTH_LONG).show()
            } catch (e: UnavailableUserDeclinedInstallationException) {
                Toast.makeText(this, "ARCore installation declined", Toast.LENGTH_LONG).show()
            } catch (e: UnavailableApkTooOldException) {
                Toast.makeText(this, "ARCore APK too old", Toast.LENGTH_LONG).show()
            } catch (e: UnavailableSdkTooOldException) {
                Toast.makeText(this, "SDK too old for ARCore", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "ARCore session creation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class ArRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
            
            // Initialize background renderer
            backgroundRenderer = BackgroundRenderer()
            backgroundRenderer?.createOnGlThread()
            
            surfaceCreated = true
            checkCameraPermissionAndSetup()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            arSession?.setDisplayGeometry(0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            
            arSession?.let { session ->
                try {
                    session.setCameraTextureName(backgroundRenderer?.textureId ?: 0)
                    val frame = session.update()
                    
                    // Draw the camera background
                    backgroundRenderer?.draw(frame)
                    
                } catch (e: CameraNotAvailableException) {
                    // Handle camera not available
                }
            }
        }
    }

    // Background renderer class for camera feed
    private class BackgroundRenderer {
        private var quadVertices: FloatBuffer? = null
        private var quadTexCoord: FloatBuffer? = null
        private var quadTexCoordTransformed: FloatBuffer? = null
        
        private var quadProgram = 0
        private var quadPositionParam = 0
        private var quadTexCoordParam = 0
        var textureId = 0
        
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            -1.0f, +1.0f, 0.0f,
            +1.0f, -1.0f, 0.0f,
            +1.0f, +1.0f, 0.0f
        )
        
        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )
        
        // NDC coordinates for transformation (same size as texture coordinates)
        private val NDC_QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, -1.0f,
            +1.0f, +1.0f
        )
        
        private val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()
        
        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()
        
        fun createOnGlThread() {
            // Generate texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            
            // Create vertex buffer (3D coordinates for rendering)
            quadVertices = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(QUAD_COORDS)
            quadVertices?.position(0)
            
            // Create texture coordinate buffer
            quadTexCoord = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(QUAD_TEXCOORDS)
            quadTexCoord?.position(0)
            
            // Create transformed texture coordinate buffer
            quadTexCoordTransformed = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            quadTexCoordTransformed?.position(0)
            
            // Create shader program
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            
            quadProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(quadProgram, vertexShader)
            GLES20.glAttachShader(quadProgram, fragmentShader)
            GLES20.glLinkProgram(quadProgram)
            
            quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
            quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            return shader
        }
        
        fun draw(frame: Frame) {
            if (frame.hasDisplayGeometryChanged()) {
                // Create NDC buffer for transformation
                val ndcBuffer = ByteBuffer.allocateDirect(NDC_QUAD_COORDS.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(NDC_QUAD_COORDS)
                ndcBuffer.position(0)
                
                try {
                    // Transform coordinates - both buffers must have same size
                    frame.transformCoordinates2d(
                        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                        ndcBuffer,
                        Coordinates2d.TEXTURE_NORMALIZED,
                        quadTexCoordTransformed
                    )
                } catch (e: Exception) {
                    // If transformation fails, use default texture coordinates
                    quadTexCoordTransformed?.put(QUAD_TEXCOORDS)
                    quadTexCoordTransformed?.position(0)
                }
            }
            
            // Draw the background
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(false)
            
            GLES20.glUseProgram(quadProgram)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            
            GLES20.glVertexAttribPointer(quadPositionParam, 3, GLES20.GL_FLOAT, false, 0, quadVertices)
            GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordTransformed)
            
            GLES20.glEnableVertexAttribArray(quadPositionParam)
            GLES20.glEnableVertexAttribArray(quadTexCoordParam)
            
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            
            GLES20.glDisableVertexAttribArray(quadPositionParam)
            GLES20.glDisableVertexAttribArray(quadTexCoordParam)
            
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }

    override fun onResume() {
        super.onResume()
        
        if (arSession != null && surfaceCreated) {
            try {
                arSession?.resume()
            } catch (e: CameraNotAvailableException) {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_LONG).show()
                arSession = null
                return
            }
        }
        
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        arSession = null
    }
}

