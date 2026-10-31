package com.crsmthw.hingegate.overlay

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withTranslation
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackAppearance
import com.crsmthw.hingegate.domain.HingeLine
import com.crsmthw.hingegate.posture.PostureSnapshot
import com.crsmthw.hingegate.render.CrackRenderer

/**
 * The overlay window's only view: draws the active crack through [CrackRenderer], in DISPLAY coordinates (offset
 * by where the window landed on screen), with the appear flicker while it lasts.
 *
 * The XML-inflation constructors exist because lint's ViewConstructor requires them on any public View; the
 * collaborators come through [bind] instead.
 */
class CrackOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var renderer: CrackRenderer? = null
    private var hingeFor: ((Int, Int) -> HingeLine)? = null
    private var onResize: (() -> Unit)? = null
    private var crack: ActiveCrack? = null
    private var snapshot: PostureSnapshot? = null

    // Reused every frame: onDraw must not allocate (lint DrawAllocation, and the flicker runs at display rate).
    private val location = IntArray(2)

    fun bind(renderer: CrackRenderer, hingeFor: (Int, Int) -> HingeLine, onResize: () -> Unit) {
        this.renderer = renderer
        this.hingeFor = hingeFor
        this.onResize = onResize
    }

    fun show(crack: ActiveCrack, snapshot: PostureSnapshot) {
        if (crack == this.crack && snapshot == this.snapshot) return
        this.crack = crack
        this.snapshot = snapshot
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The window's new size can arrive before the process configuration; let the monitor re-read the display.
        onResize?.invoke()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val crack = crack ?: return
        val renderer = renderer ?: return
        val snapshot = snapshot ?: return
        getLocationOnScreen(location)
        // The window covers the display (LAYOUT_IN_SCREEN, no inset fitting); if the system still offset it, the
        // display extends that far beyond the view's top-left.
        val screenW = width + location[0]
        val screenH = height + location[1]
        val hinge = snapshot.hinge?.takeIf { snapshot.displayWidth == screenW && snapshot.displayHeight == screenH }
            ?: hingeFor?.invoke(screenW, screenH)
            ?: return
        val rotation = display?.rotation ?: snapshot.rotation
        val now = System.currentTimeMillis()
        val alpha = CrackAppearance.alpha(now - crack.appearedAtEpochMs)

        canvas.withTranslation(-location[0].toFloat(), -location[1].toFloat()) {
            renderer.draw(this, screenW, screenH, rotation, hinge, crack.spec, resources.displayMetrics.density, alpha)
        }

        // Finite: frames are requested only inside the flicker's duration.
        if (CrackAppearance.isAnimating(crack.appearedAtEpochMs, now)) postInvalidateOnAnimation()
    }
}
