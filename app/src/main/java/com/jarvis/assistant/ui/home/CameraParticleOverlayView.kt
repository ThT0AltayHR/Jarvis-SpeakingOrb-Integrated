package com.jarvis.assistant.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight, local-only particle treatment for a face found in the front camera. */
class CameraParticleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(97, 212, 255) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 72, 190, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * resources.displayMetrics.density
    }
    private var faceX = 0.5f
    private var faceY = 0.42f
    private var faceRadius = 0.13f
    private var faceFound = false
    private var animationPhase = 0f

    fun updateDetectedFace(x: Float, y: Float, radius: Float, found: Boolean) {
        faceX = x.coerceIn(0.08f, 0.92f)
        faceY = y.coerceIn(0.12f, 0.85f)
        faceRadius = radius.coerceIn(0.06f, 0.24f)
        faceFound = found
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!faceFound || width <= 0 || height <= 0) return
        animationPhase = (animationPhase + 0.045f) % (Math.PI.toFloat() * 2f)
        val cx = faceX * width
        val cy = faceY * height
        val radius = faceRadius * width
        canvas.drawCircle(cx, cy, radius * 1.22f, linePaint)
        canvas.drawCircle(cx, cy, radius * 1.38f, linePaint.apply { alpha = 65 })
        linePaint.alpha = 135

        val density = resources.displayMetrics.density
        for (i in 0 until 42) {
            val fraction = i / 42f
            val angle = animationPhase + fraction * Math.PI.toFloat() * 2f
            val wobble = 0.92f + 0.08f * sin(angle * 3f)
            val px = cx + cos(angle) * radius * 1.34f * wobble
            val py = cy + sin(angle) * radius * 1.34f * wobble
            val dotRadius = (1.15f + (i % 4) * 0.42f) * density
            particlePaint.alpha = 100 + (i % 5) * 30
            canvas.drawCircle(px, py, dotRadius, particlePaint)
        }
        postInvalidateOnAnimation()
    }
}
