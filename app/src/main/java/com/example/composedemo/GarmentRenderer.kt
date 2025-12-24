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
            // Torso grid: width=14, height=20 (from metadata.mesh.torsoGrid)
            if (deformedMesh.torsoVertices.isNotEmpty()) {
                renderRegion(deformedMesh.torsoVertices, gridWidth = 14, gridHeight = 20)
            }

            // Render sleeves
            // Sleeve grid: width=8, height=16 (from metadata.mesh.sleeveGrid)
            if (deformedMesh.leftSleeveVertices.isNotEmpty()) {
                renderRegion(deformedMesh.leftSleeveVertices, gridWidth = 8, gridHeight = 16)
            }
            if (deformedMesh.rightSleeveVertices.isNotEmpty()) {
                renderRegion(deformedMesh.rightSleeveVertices, gridWidth = 8, gridHeight = 16)
            }

            GLES20.glDisable(GLES20.GL_BLEND)
        } catch (e: Exception) {
            android.util.Log.e("GarmentRenderer", "Error rendering mesh", e)
        }
    }

    /**
     * Renders a single region (torso or sleeve) as triangles.
     * Uses indexed rendering with GL_TRIANGLES to correctly render 2D grid topology.
     * 
     * @param vertices List of deformed vertices in row-major grid order
     * @param gridWidth Number of vertices per row (columns)
     * @param gridHeight Number of vertices per column (rows)
     */
    private fun renderRegion(vertices: List<DeformedVertex>, gridWidth: Int, gridHeight: Int) {
        if (vertices.size < 3) return

        // Validate grid dimensions match vertex count
        val vertexCount = vertices.size
        val expectedVertexCount = gridWidth * gridHeight
        if (vertexCount != expectedVertexCount) {
            android.util.Log.e("GarmentRenderer", 
                "Grid dimension mismatch: vertexCount=$vertexCount, expected=$expectedVertexCount (${gridWidth}x${gridHeight})")
            return
        }

        try {
            // Create vertex and texture coordinate buffers
            val vertexBuffer = createVertexBuffer(vertices)
            val texCoordBuffer = createTexCoordBuffer(vertices)
            
            // Generate index buffer for 2D grid topology using actual grid dimensions
            // Indices are required because row-major vertex order cannot form a valid triangle strip
            val (indexBuffer, indexCount) = createIndexBuffer(gridWidth, gridHeight, vertexCount)

            val positionHandle = GLES20.glGetAttribLocation(programHandle, "a_Position")
            val texCoordHandle = GLES20.glGetAttribLocation(programHandle, "a_TexCoord")

            if (positionHandle < 0 || texCoordHandle < 0) {
                android.util.Log.w("GarmentRenderer", "Invalid attribute handles")
                return
            }

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle, 4, GLES20.GL_FLOAT, false,
                4 * 4, vertexBuffer  // 4 floats per vertex (x, y, z, w)
            )

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(
                texCoordHandle, 2, GLES20.GL_FLOAT, false,
                2 * 4, texCoordBuffer
            )

            // Render using indexed triangles with correct index count
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                indexCount,
                GLES20.GL_UNSIGNED_SHORT,
                indexBuffer
            )

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        } catch (e: Exception) {
            android.util.Log.e("GarmentRenderer", "Error rendering region", e)
        }
    }
    
    /**
     * Creates index buffer for a 2D grid mesh stored in row-major order.
     * Generates two triangles per quad cell in the grid.
     * 
     * Grid topology: vertices are arranged as rows × cols (row-major order)
     * For each quad cell, creates indices:
     *   Triangle 1: v0, v2, v1 (top-left, bottom-left, top-right)
     *   Triangle 2: v1, v2, v3 (top-right, bottom-left, bottom-right)
     * 
     * @param gridWidth Number of vertices per row (columns)
     * @param gridHeight Number of vertices per column (rows)
     * @param vertexCount Total vertex count (must equal gridWidth * gridHeight)
     * @return Pair of (index buffer, index count)
     */
    private fun createIndexBuffer(gridWidth: Int, gridHeight: Int, vertexCount: Int): Pair<java.nio.ShortBuffer, Int> {
        // Validate grid dimensions
        val expectedVertexCount = gridWidth * gridHeight
        require(vertexCount == expectedVertexCount) {
            "Grid dimension mismatch: vertexCount=$vertexCount, expected=$expectedVertexCount (${gridWidth}x${gridHeight})"
        }
        
        val cols = gridWidth
        val rows = gridHeight
        
        // Each quad cell needs 2 triangles × 3 indices = 6 indices
        // Number of quads = (rows - 1) * (cols - 1)
        val numQuads = (rows - 1) * (cols - 1)
        val numIndices = numQuads * 6
        
        val buffer = java.nio.ByteBuffer.allocateDirect(numIndices * 2)  // 2 bytes per short
        buffer.order(java.nio.ByteOrder.nativeOrder())
        val indexBuffer = buffer.asShortBuffer()
        
        // Generate indices for each quad in the grid
        // Vertices are stored in row-major order: row0_col0, row0_col1, ..., row0_colN, row1_col0, ...
        for (y in 0 until rows - 1) {
            for (x in 0 until cols - 1) {
                // Calculate vertex indices for this quad (row-major indexing)
                val v0 = (y * cols + x).toShort()           // Top-left
                val v1 = (y * cols + x + 1).toShort()       // Top-right
                val v2 = ((y + 1) * cols + x).toShort()     // Bottom-left
                val v3 = ((y + 1) * cols + x + 1).toShort() // Bottom-right
                
                // Validate indices are within bounds
                val maxIndex = maxOf(v0.toInt(), v1.toInt(), v2.toInt(), v3.toInt())
                require(maxIndex < vertexCount) {
                    "Index buffer out of bounds: maxIndex=$maxIndex, vertexCount=$vertexCount at quad ($x, $y)"
                }
                
                // First triangle: v0, v2, v1 (counter-clockwise)
                indexBuffer.put(v0)
                indexBuffer.put(v2)
                indexBuffer.put(v1)
                
                // Second triangle: v1, v2, v3 (counter-clockwise)
                indexBuffer.put(v1)
                indexBuffer.put(v2)
                indexBuffer.put(v3)
            }
        }
        
        indexBuffer.position(0)
        return Pair(indexBuffer, numIndices)
    }

    private fun createVertexBuffer(vertices: List<DeformedVertex>): FloatBuffer {
        // OpenGL expects vec4 (x, y, z, w) for position
        // For 2D rendering: z = 0, w = 1
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4 * 4)  // 4 floats per vertex
        buffer.order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()

        for (vertex in vertices) {
            floatBuffer.put(vertex.screenX)  // X in NDC space (-1 to +1)
            floatBuffer.put(vertex.screenY)  // Y in NDC space (-1 to +1)
            floatBuffer.put(0f)              // Z = 0 for 2D
            floatBuffer.put(1f)              // W = 1 for homogeneous coordinates
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

