package com.example.arcoredemo

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
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
import java.io.IOException
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
    private var planeRenderer: PlaneRenderer? = null
    private var modelRenderer: ModelRenderer? = null
    private val anchors = mutableListOf<Anchor>()

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
                    
                    // Add touch listener for model placement
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            handleTouch(event.x, event.y)
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    private fun handleTouch(x: Float, y: Float) {
        val frame = arSession?.update() ?: return
        
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }

        val hits = frame.hitTest(x, y)
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                // Create anchor at hit location
                val anchor = hit.createAnchor()
                anchors.add(anchor)
                Toast.makeText(this, "Model placed!", Toast.LENGTH_SHORT).show()
                break
            }
        }
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
            
            // Initialize renderers
            backgroundRenderer = BackgroundRenderer()
            backgroundRenderer?.createOnGlThread()
            
            planeRenderer = PlaneRenderer()
            planeRenderer?.createOnGlThread()
            
            modelRenderer = ModelRenderer()
            modelRenderer?.createOnGlThread(this@MainActivity)
            
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
                    
                    if (frame.camera.trackingState == TrackingState.TRACKING) {
                        // Get projection matrix
                        val projectionMatrix = FloatArray(16)
                        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                        
                        // Get view matrix
                        val viewMatrix = FloatArray(16)
                        frame.camera.getViewMatrix(viewMatrix, 0)
                        
                        // Draw planes
                        planeRenderer?.drawPlanes(session.getAllTrackables(Plane::class.java), viewMatrix, projectionMatrix)
                        
                        // Draw models at anchor positions
                        modelRenderer?.drawModels(anchors, viewMatrix, projectionMatrix)
                    }
                    
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

    // Plane renderer to visualize detected planes
    private class PlaneRenderer {
        private var program = 0
        private var positionAttribute = 0
        private var modelViewProjectionUniform = 0
        private var colorUniform = 0
        
        private val VERTEX_SHADER = """
            attribute vec4 a_Position;
            uniform mat4 u_ModelViewProjection;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """.trimIndent()
        
        private val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()
        
        fun createOnGlThread() {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            
            positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
            modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
            colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            return shader
        }
        
        fun drawPlanes(planes: Collection<Plane>, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
            GLES20.glUseProgram(program)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            
            for (plane in planes) {
                if (plane.trackingState != TrackingState.TRACKING) continue
                
                val vertices = plane.polygon
                if (vertices.remaining() == 0) continue
                
                val vertexCount = vertices.remaining() / 2 // 2D coordinates
                val vertexBuffer = ByteBuffer.allocateDirect(vertexCount * 3 * 4) // 3D coordinates
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                
                // Convert 2D polygon to 3D vertices
                val pose = plane.centerPose
                vertices.rewind()
                for (i in 0 until vertexCount) {
                    val x = vertices.get()
                    val z = vertices.get()
                    val localPoint = floatArrayOf(x, 0.0f, z, 1.0f)
                    val worldPoint = FloatArray(4)
                    val poseMatrix = FloatArray(16)
                    pose.toMatrix(poseMatrix, 0)
                    
                    // Transform local point to world coordinates
                    Matrix.multiplyMV(worldPoint, 0, poseMatrix, 0, localPoint, 0)
                    
                    vertexBuffer.put(worldPoint[0])
                    vertexBuffer.put(worldPoint[1])
                    vertexBuffer.put(worldPoint[2])
                }
                vertexBuffer.position(0)
                
                val modelMatrix = FloatArray(16)
                Matrix.setIdentityM(modelMatrix, 0)
                
                val modelViewMatrix = FloatArray(16)
                val modelViewProjectionMatrix = FloatArray(16)
                Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
                
                GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glEnableVertexAttribArray(positionAttribute)
                GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
                GLES20.glUniform4f(colorUniform, 0.0f, 1.0f, 0.0f, 0.3f) // Semi-transparent green
                
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
                GLES20.glDisableVertexAttribArray(positionAttribute)
            }
            
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    // Model renderer for GLB models
    private class ModelRenderer {
        private var program = 0
        private var positionAttribute = 0
        private var modelViewProjectionUniform = 0
        private var colorUniform = 0
        private var cubeVertices: FloatBuffer? = null
        
        private val CUBE_VERTICES = floatArrayOf(
            // Front face
            -0.1f, -0.1f,  0.1f,
             0.1f, -0.1f,  0.1f,
             0.1f,  0.1f,  0.1f,
            -0.1f,  0.1f,  0.1f,
            // Back face
            -0.1f, -0.1f, -0.1f,
             0.1f, -0.1f, -0.1f,
             0.1f,  0.1f, -0.1f,
            -0.1f,  0.1f, -0.1f
        )
        
        private val VERTEX_SHADER = """
            attribute vec4 a_Position;
            uniform mat4 u_ModelViewProjection;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """.trimIndent()
        
        private val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()
        
        fun createOnGlThread(context: MainActivity) {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            
            positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
            modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
            colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
            
            // Create cube vertices buffer
            cubeVertices = ByteBuffer.allocateDirect(CUBE_VERTICES.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(CUBE_VERTICES)
            cubeVertices?.position(0)
            
            // Try to load GLB model
            try {
                loadGLBModel(context)
            } catch (e: Exception) {
                // If GLB loading fails, use simple cube
                Toast.makeText(context, "GLB model loading not yet implemented, showing cube", Toast.LENGTH_LONG).show()
            }
        }
        
        private fun loadGLBModel(context: MainActivity) {
            // TODO: Implement GLB model loading
            // For now, we'll use a simple cube as placeholder
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            return shader
        }
        
        fun drawModels(anchors: List<Anchor>, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
            if (anchors.isEmpty()) return
            
            GLES20.glUseProgram(program)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            
            for (anchor in anchors) {
                if (anchor.trackingState != TrackingState.TRACKING) continue
                
                val modelMatrix = FloatArray(16)
                anchor.pose.toMatrix(modelMatrix, 0)
                
                val modelViewMatrix = FloatArray(16)
                val modelViewProjectionMatrix = FloatArray(16)
                Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
                
                GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
                GLES20.glEnableVertexAttribArray(positionAttribute)
                GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
                GLES20.glUniform4f(colorUniform, 1.0f, 0.0f, 0.0f, 1.0f) // Red color
                
                // Draw cube as wireframe
                for (i in 0 until 8 step 4) {
                    GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, i, 4)
                }
                
                GLES20.glDisableVertexAttribArray(positionAttribute)
            }
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

