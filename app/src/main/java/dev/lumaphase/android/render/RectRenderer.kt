package dev.lumaphase.android.render

import android.opengl.GLES20
import android.util.Log
import dev.lumaphase.android.layout.LevelRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws axis-aligned solid rectangles straight in final drawable output pixels.
 *
 * Vertices are converted to normalised device coordinates on the CPU from the same
 * integers that [dev.lumaphase.android.layout.LayoutJson] exports, so the drawn geometry
 * cannot drift from the exported layout. There are no textures, so no filtering;
 * blending and dithering are explicitly disabled and the EGL configuration requests no
 * multisampling, so rectangle edges stay hard and the code values are not modified.
 *
 * All calls must happen on the thread with the current EGL context.
 */
class RectRenderer {
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var bufferName = 0
    private var vertices: FloatBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create(): Boolean {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) return false

        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertexShader)
        GLES20.glAttachShader(linked, fragmentShader)
        GLES20.glLinkProgram(linked)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(linked)}")
            GLES20.glDeleteProgram(linked)
            return false
        }
        program = linked
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        val names = IntArray(1)
        GLES20.glGenBuffers(1, names, 0)
        bufferName = names[0]
        if (positionHandle < 0 || colorHandle < 0 || bufferName == 0) {
            Log.e(TAG, "program is unusable: aPosition=$positionHandle aColor=$colorHandle vbo=$bufferName")
            return false
        }
        Log.i(TAG, "program=$program aPosition=$positionHandle aColor=$colorHandle vbo=$bufferName")
        return true
    }

    fun draw(rects: List<LevelRect>, viewportWidthPx: Int, viewportHeightPx: Int) {
        if (program == 0 || viewportWidthPx <= 0 || viewportHeightPx <= 0) return

        GLES20.glViewport(0, 0, viewportWidthPx, viewportHeightPx)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (rects.isEmpty()) return

        ensureCapacity(rects.size * VERTICES_PER_RECT)
        val buffer = vertices
        buffer.clear()
        val width = viewportWidthPx.toFloat()
        val height = viewportHeightPx.toFloat()
        for (rect in rects) {
            val left = 2f * rect.rect.x0 / width - 1f
            val right = 2f * rect.rect.x1 / width - 1f
            val top = 1f - 2f * rect.rect.y0 / height
            val bottom = 1f - 2f * rect.rect.y1 / height
            val level = rect.level.coerceIn(0, 255) / 255f
            putVertex(buffer, left, top, level)
            putVertex(buffer, right, top, level)
            putVertex(buffer, right, bottom, level)
            putVertex(buffer, left, top, level)
            putVertex(buffer, right, bottom, level)
            putVertex(buffer, left, bottom, level)
        }
        buffer.flip()

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferName)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            buffer.remaining() * BYTES_PER_FLOAT,
            buffer,
            GLES20.GL_DYNAMIC_DRAW,
        )
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(
            colorHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            2 * BYTES_PER_FLOAT,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, rects.size * VERTICES_PER_RECT)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        if (bufferName != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(bufferName), 0)
            bufferName = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun ensureCapacity(vertexCount: Int) {
        val needed = vertexCount * FLOATS_PER_VERTEX
        if (vertices.capacity() >= needed) return
        vertices = ByteBuffer.allocateDirect(needed * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun putVertex(buffer: FloatBuffer, x: Float, y: Float, level: Float) {
        buffer.put(x).put(y).put(level).put(level).put(level)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        const val TAG = "OptcRects"
        const val FLOATS_PER_VERTEX = 5
        const val VERTICES_PER_RECT = 6
        const val BYTES_PER_FLOAT = 4
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * BYTES_PER_FLOAT

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        /**
         * The colour arrives as an exactly converted normalised float and is written
         * unchanged. highp is requested where the driver offers it so the value maps back
         * to the same 8-bit code value.
         */
        const val FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """
    }
}
