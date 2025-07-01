package com.example.arcoredemo

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {
    
    private var arSession: Session? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var surfaceCreated = false
    private var installRequested = false

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
                    session.setCameraTextureName(0)
                    val frame = session.update()
                    
                    // Basic rendering - just clear the screen
                    // In a real app, you would render the camera background and AR objects here
                    
                } catch (e: CameraNotAvailableException) {
                    // Handle camera not available
                }
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

