package com.jarvis.assistant.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** A light, GPU-cheap dust settling reveal for completed assistant messages. */
class DustRevealTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private data class Particle(val sx: Float, val sy: Float, val tx: Float, val ty: Float, val radius: Float, val phase: Float)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var revealProgress = 1f
    private var particles: List<Particle> = emptyList()
    private var revealAnimator: ValueAnimator? = null

    fun playDustReveal() {
        if (!isAttachedToWindow || width <= 0 || height <= 0 || text.isNullOrBlank()) {
            revealProgress = 1f
            invalidate()
            return
        }
        revealAnimator?.cancel()
        val count = 42
        particles = (0 until count).map { i ->
            val seed = (i + 1) * 97.31f
            val sx = ((sin(seed) + 1f) * .5f) * width
            val sy = ((cos(seed * 1.7f) + 1f) * .5f) * height
            val tx = ((sin(seed * 2.3f) + 1f) * .5f) * width
            val ty = ((cos(seed * 2.9f) + 1f) * .5f) * height
            Particle(sx, sy, tx, ty, 0.8f + (i % 4) * .32f, (i % 9) / 9f)
        }
        revealProgress = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { revealProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val progress = revealProgress.coerceIn(0f, 1f)
        if (progress >= 0.999f || particles.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (progress * 255).toInt())
        super.onDraw(canvas)
        canvas.restoreToCount(save)

        val settle = progress * progress * (3f - 2f * progress)
        particles.forEachIndexed { index, p ->
            val delayed = ((settle - p.phase * .22f) / .78f).coerceIn(0f, 1f)
            if (delayed <= 0f) return@forEachIndexed
            val x = p.sx + (p.tx - p.sx) * delayed
            val y = p.sy + (p.ty - p.sy) * delayed
            val alpha = ((1f - delayed) * 220f).toInt().coerceIn(0, 220)
            if (alpha <= 0) return@forEachIndexed
            particlePaint.color = Color.argb(alpha, 128 + (index % 4) * 18, 190 + (index % 3) * 16, 255)
            particlePaint.setShadowLayer(2.4f, 0f, 0f, Color.argb(alpha / 2, 82, 154, 255))
            canvas.drawCircle(x, y, p.radius * (1f - delayed * .25f), particlePaint)
        }
        particlePaint.clearShadowLayer()
    }

    override fun onDetachedFromWindow() {
        revealAnimator?.cancel()
        revealAnimator = null
        super.onDetachedFromWindow()
    }
}
