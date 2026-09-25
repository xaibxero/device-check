package com.devicecheck.app.audit

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import java.security.MessageDigest

data class EglGpuTelemetry(
    val renderer: String,
    val vendor: String,
    val openGlVersion: String,
    val extensionsHash: String,
    val extensionCount: Int
)

object EglGpuAuditor {

    fun audit(): EglGpuTelemetry {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return fallback("EGL_NO_DISPLAY")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fallback("EGL_INIT_FAIL")

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: return fallback("EGL_CONFIG_NULL")

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val surface: EGLSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val context: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        EGL14.eglMakeCurrent(display, surface, surface, context)

        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "UNKNOWN"
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "UNKNOWN"
        val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "UNKNOWN"
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""

        val extList = extensions.split(" ").filter { it.isNotBlank() }
        val extHash = sha256(extList.sorted().joinToString(","))

        // Clean up EGL resources
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)

        return EglGpuTelemetry(
            renderer = renderer,
            vendor = vendor,
            openGlVersion = glVersion,
            extensionsHash = extHash.take(16).uppercase(),
            extensionCount = extList.size
        )
    }

    private fun fallback(reason: String) = EglGpuTelemetry(
        renderer = "Adreno (TM) / Fallback ($reason)",
        vendor = "Qualcomm",
        openGlVersion = "OpenGL ES 3.2",
        extensionsHash = "N/A",
        extensionCount = 0
    )

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
