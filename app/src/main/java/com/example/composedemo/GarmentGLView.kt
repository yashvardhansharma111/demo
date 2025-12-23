package com.example.composedemo

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL SurfaceView for rendering deformed garment mesh.
 * Integrates with Jetpack Compose via AndroidView.
 */
class GarmentGLView(context: Context) : GLSurfaceView(context) {
    private val renderer: GarmentGLRenderer

    init {
        try {
            // CRITICAL: GLSurfaceView composited transparently over CameraX PreviewView
            // The GLSurfaceView must NOT be on top (setZOrderOnTop(false)) so it can composite
            // with the camera preview layer beneath it, rather than occluding it.
            setEGLContextClientVersion(2)
            // EGL config with alpha channel: R, G, B, A, depth, stencil
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            // CRITICAL: setZOrderOnTop(false) allows transparent compositing with camera layer
            // If true, the GLSurfaceView would occlude the camera preview completely
            setZOrderOnTop(false)
            // Set surface format to TRANSLUCENT for transparency support
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            // Set transparent background
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            renderer = GarmentGLRenderer(context, this)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY // Update only when dirty
        } catch (e: Exception) {
            android.util.Log.e("GarmentGLView", "Failed to initialize OpenGL", e)
            throw e
        }
    }

    fun updateMesh(deformedMesh: DeformedMesh?) {
        renderer.updateMesh(deformedMesh)
        requestRender()
    }

    fun setTexture(resourceId: Int) {
        // Store texture ID - will be loaded in onSurfaceCreated
        renderer.setTextureResourceId(resourceId)
    }

}

/**
 * OpenGL renderer that draws deformed mesh.
 */
class GarmentGLRenderer(
    private val context: Context,
    private val glSurfaceView: GLSurfaceView
) : GLSurfaceView.Renderer {
    private val garmentRenderer: GarmentRenderer
    private var currentMesh: DeformedMesh? = null
    private var textureResourceId: Int = 0
    private var surfaceCreated = false

    init {
        // Create renderer but don't initialize shaders yet (no OpenGL context)
        garmentRenderer = GarmentRenderer(context)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // Now we have OpenGL context - initialize shaders
            garmentRenderer.initialize()
            
            // CRITICAL: Clear with transparent color (0, 0, 0, 0) so camera preview shows through
            // This transparent clear color is required for proper compositing with the camera layer
            // Without it, the OpenGL surface would have an opaque background that occludes the camera
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
            
            surfaceCreated = true
            
            // Load texture if resource ID was set before surface creation
            if (textureResourceId != 0) {
                garmentRenderer.loadTexture(textureResourceId)
            }
        } catch (e: Exception) {
            android.util.Log.e("GarmentGLRenderer", "Error in onSurfaceCreated", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        garmentRenderer.setProjection(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            // Clear with transparent color (already set in onSurfaceCreated)
            // Only clear color buffer, not depth buffer (for better performance with transparency)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            currentMesh?.let { mesh ->
                garmentRenderer.render(mesh)
            }
        } catch (e: Exception) {
            android.util.Log.e("GarmentGLRenderer", "Error in onDrawFrame", e)
        }
    }

    fun updateMesh(mesh: DeformedMesh?) {
        currentMesh = mesh
    }
    
    fun setTextureResourceId(resourceId: Int) {
        textureResourceId = resourceId
        // If surface is already created, load texture immediately
        // Otherwise, it will be loaded in onSurfaceCreated
        if (surfaceCreated && textureResourceId != 0) {
            glSurfaceView.queueEvent {
                try {
                    garmentRenderer.loadTexture(textureResourceId)
                } catch (e: Exception) {
                    android.util.Log.e("GarmentGLRenderer", "Error loading texture", e)
                }
            }
        }
    }
    
    fun getTextureResourceId(): Int = textureResourceId
}

