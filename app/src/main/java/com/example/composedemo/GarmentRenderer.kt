package com.example.composedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * OpenGL ES renderer for deformed garment mesh.
 * Maintains 60 FPS by uploading vertex data each frame.
 */
class GarmentRenderer(private val context: Context) {
    private var textureId: Int = 0
    private var programHandle: Int = 0
    private var textureResourceId: Int = 0

    // Shader source code
    private val vertexShaderCode = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        uniform mat4 u_MVPMatrix;
        
        void main() {
            gl_Position = u_MVPMatrix * a_Position;
            v_TexCoord = a_TexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform sampler2D u_Texture;
        
        void main() {
            vec4 color = texture2D(u_Texture, v_TexCoord);
            // Discard fully transparent pixels for performance
            if (color.a < 0.01) {
                discard;
            }
            gl_FragColor = color;
        }
    """.trimIndent()

    // OpenGL buffers
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    
    private var isInitialized = false

    init {
        Matrix.setIdentityM(viewMatrix, 0)
    }
    
    /**
     * Initialize shaders - must be called after OpenGL context is created.
     */
    fun initialize() {
        if (!isInitialized) {
            initializeShaders()
            isInitialized = true
        }
    }

    /**
     * Initializes OpenGL shaders and program.
     */
    private fun initializeShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programHandle = GLES20.glCreateProgram()
        GLES20.glAttachShader(programHandle, vertexShader)
        GLES20.glAttachShader(programHandle, fragmentShader)
        GLES20.glLinkProgram(programHandle)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES20.glGetProgramInfoLog(programHandle)
            throw RuntimeException("Shader program link failed: $infoLog")
        }
    }

    /**
     * Sets the texture resource ID (will be loaded when OpenGL is ready).
     */
    fun setTextureResourceId(resourceId: Int) {
        textureResourceId = resourceId
    }
    
    /**
     * Gets the texture resource ID.
     */
    fun getTextureResourceId(): Int = textureResourceId
    
    /**
     * Checks if renderer is initialized.
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Loads texture from bitmap resource.
     */
    fun loadTexture(resourceId: Int) {
        if (!isInitialized) {
            android.util.Log.w("GarmentRenderer", "Renderer not initialized, storing resource ID")
            textureResourceId = resourceId
            return
        }
        
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, resourceId)
            loadTexture(bitmap)
        } catch (e: Exception) {
            android.util.Log.e("GarmentRenderer", "Error loading texture from resource", e)
            throw e
        }
    }

    /**
     * Loads texture from bitmap.
     */
    fun loadTexture(bitmap: Bitmap) {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)

        if (textureHandle[0] == 0) {
            throw RuntimeException("Error generating texture name")
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])

        // Set texture parameters
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        textureId = textureHandle[0]
        bitmap.recycle()
    }

    /**
     * Sets up projection matrix for OpenGL NDC space (-1 to +1).
     * Since vertices are already in NDC space, we use identity projection.
     */
    fun setProjection(width: Int, height: Int) {
        // Use identity matrix - vertices are already in NDC space (-1 to +1)
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    /**
     * Renders deformed mesh to screen.
     * Called each frame (target: 60 FPS).
     */
    fun render(deformedMesh: DeformedMesh) {
        if (!isInitialized || programHandle == 0 || textureId == 0) {
            android.util.Log.w("GarmentRenderer", "Renderer not initialized, skipping render")
            return
        }

        try {
            GLES20.glUseProgram(programHandle)

            // Bind texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            val textureUniform = GLES20.glGetUniformLocation(programHandle, "u_Texture")
            if (textureUniform >= 0) {
                GLES20.glUniform1i(textureUniform, 0)
            }

            // Set MVP matrix
            val mvpUniform = GLES20.glGetUniformLocation(programHandle, "u_MVPMatrix")
            if (mvpUniform >= 0) {
                GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
            }

            // Enable blending for transparency
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            // Render torso
            if (deformedMesh.torsoVertices.isNotEmpty()) {
                renderRegion(deformedMesh.torsoVertices)
            }

            // Render sleeves
            if (deformedMesh.leftSleeveVertices.isNotEmpty()) {
                renderRegion(deformedMesh.leftSleeveVertices)
            }
            if (deformedMesh.rightSleeveVertices.isNotEmpty()) {
                renderRegion(deformedMesh.rightSleeveVertices)
            }

            GLES20.glDisable(GLES20.GL_BLEND)
        } catch (e: Exception) {
            android.util.Log.e("GarmentRenderer", "Error rendering mesh", e)
        }
    }

    /**
     * Renders a single region (torso or sleeve) as triangles.
     */
    private fun renderRegion(vertices: List<DeformedVertex>) {
        if (vertices.size < 3) return

        try {
            // Convert vertices to triangle strips
            // For a grid, we render as triangle strips
            val vertexBuffer = createVertexBuffer(vertices)
            val texCoordBuffer = createTexCoordBuffer(vertices)

            val positionHandle = GLES20.glGetAttribLocation(programHandle, "a_Position")
            val texCoordHandle = GLES20.glGetAttribLocation(programHandle, "a_TexCoord")

            if (positionHandle < 0 || texCoordHandle < 0) {
                android.util.Log.w("GarmentRenderer", "Invalid attribute handles")
                return
            }

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle, 2, GLES20.GL_FLOAT, false,
                2 * 4, vertexBuffer
            )

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(
                texCoordHandle, 2, GLES20.GL_FLOAT, false,
                2 * 4, texCoordBuffer
            )

            // Render as triangle strips (simplified - assumes grid topology)
            // For production, use indexed rendering with proper grid topology
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertices.size)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        } catch (e: Exception) {
            android.util.Log.e("GarmentRenderer", "Error rendering region", e)
        }
    }

    private fun createVertexBuffer(vertices: List<DeformedVertex>): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(vertices.size * 2 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()

        for (vertex in vertices) {
            floatBuffer.put(vertex.screenX)
            floatBuffer.put(vertex.screenY)
        }
        floatBuffer.position(0)
        return floatBuffer
    }

    private fun createTexCoordBuffer(vertices: List<DeformedVertex>): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(vertices.size * 2 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()

        for (vertex in vertices) {
            floatBuffer.put(vertex.u)
            floatBuffer.put(vertex.v)
        }
        floatBuffer.position(0)
        return floatBuffer
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $infoLog")
        }

        return shader
    }

    fun release() {
        if (textureId != 0) {
            val textures = IntArray(1)
            textures[0] = textureId
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = 0
        }
        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
    }
}

