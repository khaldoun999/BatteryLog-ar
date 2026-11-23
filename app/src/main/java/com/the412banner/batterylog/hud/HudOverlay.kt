package com.the412banner.batterylog.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** The floating HUD window ("display over other apps"). All calls may come from any thread. */
class HudOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var applied: HudConfig? = null

    fun allowed(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(parts: List<HudPart>, cfg: HudConfig) {
        main.post {
            if (!allowed()) { removeNow(); return@post }
            val v = view ?: create(cfg) ?: return@post
            if (applied != cfg) style(v, cfg)
            v.text = spans(parts, cfg.oneLine)
        }
    }

    fun hide() { main.post { removeNow() } }

    private fun create(cfg: HudConfig): TextView? {
        val v = TextView(ctx)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        p.title = "عارض سجل البطارية"
        params = p
        view = v
        style(v, cfg)
        attachDrag(v)
        return if (runCatching { wm.addView(v, p) }.isSuccess) v else { view = null; params = null; null }
    }

    private fun style(v: TextView, cfg: HudConfig) {
        val p = params ?: return
        val sp = when (cfg.size) { 0 -> 10f; 2 -> 16f; else -> 12.5f }
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        v.setTextColor(Color.WHITE)
        v.setShadowLayer(2f, 0f, 1f, Color.BLACK)
        val d = ctx.resources.displayMetrics.density
        v.setPadding((7 * d).toInt(), (3 * d).toInt(), (7 * d).toInt(), (3 * d).toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = 7 * d
            setColor(Color.argb((cfg.opacity.coerceIn(0, 100) * 255) / 100, 0, 0, 0))
        }
        p.gravity = (when (cfg.row) { 1 -> Gravity.CENTER_VERTICAL; 2 -> Gravity.BOTTOM; else -> Gravity.TOP }) or
            (when (cfg.col) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START })
        p.x = cfg.x
        p.y = cfg.y
        // Android lets touches pass through another app's window only when it is at most 80 % opaque.
        if (cfg.locked) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            p.alpha = 0.8f
        } else {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            p.alpha = 1f
        }
        applied = cfg
        if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, p) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: TextView) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            val cfg = applied ?: return@setOnTouchListener false
            if (cfg.locked) return@setOnTouchListener false
            // Offsets grow away from the anchored edge; on a centered axis they are signed from the middle.
            val signX = if (cfg.col == 2) -1 else 1
            val signY = if (cfg.row == 2) -1 else 1
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (startX + signX * (e.rawX - downX)).toInt()
                    val ny = (startY + signY * (e.rawY - downY)).toInt()
                    p.x = if (cfg.col == 1) nx else nx.coerceAtLeast(0)
                    p.y = if (cfg.row == 1) ny else ny.coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(v, p) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val nx = p.x; val ny = p.y
                    HudStore.update { it.copy(x = nx, y = ny) }
                    applied = HudStore.current()
                    true
                }
                else -> false
            }
        }
    }

    private fun spans(parts: List<HudPart>, oneLine: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) sb.append(if (oneLine) "  " else "\n")
            if (part.label.isNotEmpty()) {
                val s = sb.length
                // No-break space: a wrapped HUD breaks between items, never between a label and its value.
                sb.append(part.label).append('\u00A0')
                sb.setSpan(ForegroundColorSpan(Color.rgb(170, 180, 190)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val s = sb.length
            sb.append(part.value)
            val color = when (part.level) { 2 -> Color.rgb(255, 90, 80); 1 -> Color.rgb(255, 200, 70); else -> Color.WHITE }
            sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun removeNow() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        params = null
        applied = null
    }
}
