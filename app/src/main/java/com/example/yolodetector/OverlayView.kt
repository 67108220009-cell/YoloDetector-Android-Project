package com.example.yolodetector

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<DetectionResult> = emptyList()

    private val labels = listOf(
        "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
        "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
        "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
        "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
        "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
        "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
        "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake",
        "chair","couch","potted plant","bed","dining table","toilet","tv","laptop",
        "mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink",
        "refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
    )

    private val palette = intArrayOf(
        0xFF00FFF7.toInt(),
        0xFFFF2D78.toInt(),
        0xFF7BFF00.toInt(),
        0xFFFF9500.toInt(),
        0xFFBF5FFF.toInt(),
        0xFF00CFFF.toInt(),
        0xFFFF4141.toInt(),
        0xFFFFE600.toInt(),
    )

    // ── Paints (สร้างครั้งเดียว) ─────────────────────────────────────────────

    // กรอบหลัก — ไม่มี blur (BlurMaskFilter ทำให้ช้ามาก บน SW canvas)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // fill โปร่งแสง
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // corner bracket
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.SQUARE
    }

    // label tag background
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    // label tag border
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    // left accent bar
    private val accentPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // class name text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        letterSpacing = 0.05f
    }

    // confidence text
    private val scoreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        typeface = Typeface.create("monospace", Typeface.NORMAL)
    }

    // center dot
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // reusable RectF
    private val _rect    = RectF()
    private val _tagRect = RectF()

    // ── Public API ───────────────────────────────────────────────────────────

    fun setResults(
        detectionResults: List<DetectionResult>,
        imgHeight: Int,
        imgWidth: Int
    ) {
        results = detectionResults
        // ใช้ hardware layer เพื่อ composite บน GPU แทน CPU
        // (set ทุกครั้งที่ results เปลี่ยนเป็น HARDWARE เพื่อ cache draw calls)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        invalidate()
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty()) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        results.forEachIndexed { idx, result ->
            val color = palette[idx % palette.size]

            val box    = result.boundingBox
            val left   = box.left   * vw
            val top    = box.top    * vh
            val right  = box.right  * vw
            val bottom = box.bottom * vh
            _rect.set(left, top, right, bottom)

            val bw = right - left
            val bh = bottom - top

            // 1. Fill โปร่งแสง (alpha ต่ำ — วาดเร็ว)
            fillPaint.color = color
            fillPaint.alpha = 22
            canvas.drawRect(_rect, fillPaint)

            // 2. กรอบหลัก — ไม่มี blur
            boxPaint.color = color
            boxPaint.alpha = 220
            canvas.drawRect(_rect, boxPaint)

            // 3. Corner brackets
            val cornerLen = min(bw * 0.20f, min(bh * 0.20f, 48f))
            cornerPaint.color = color
            cornerPaint.alpha = 255
            drawCornerBrackets(canvas, left, top, right, bottom, cornerLen)

            // 4. Center dot (เล็กลง วาดเร็วกว่า)
            val cx = (left + right) * 0.5f
            val cy = (top  + bottom) * 0.5f
            dotPaint.color = color
            dotPaint.alpha = 200
            canvas.drawCircle(cx, cy, 3.5f, dotPaint)

            // 5. Label tag
            val className = labels.getOrNull(result.classIndex) ?: "cls_${result.classIndex}"
            val conf      = (result.score * 100).toInt()
            val labelText = className.uppercase()
            val scoreText = "$conf%"

            val textW    = textPaint.measureText(labelText) +
                    scoreTextPaint.measureText("  $scoreText") + 24f
            val textH    = 40f
            val tagLeft  = left
            val tagBottom = if (top - textH - 6f < 0f) bottom + textH + 6f else top - 6f
            val tagTop   = tagBottom - textH
            val tagRight = tagLeft + textW

            // bg
            labelBgPaint.alpha = 190
            _tagRect.set(tagLeft, tagTop, tagRight, tagBottom)
            canvas.drawRect(_tagRect, labelBgPaint)

            // accent bar
            accentPaint.color = color
            canvas.drawRect(tagLeft, tagTop, tagLeft + 4f, tagBottom, accentPaint)

            // border
            labelBorderPaint.color = color
            labelBorderPaint.alpha = 200
            canvas.drawRect(_tagRect, labelBorderPaint)

            // text
            textPaint.color = Color.WHITE
            canvas.drawText(labelText, tagLeft + 12f, tagBottom - 8f, textPaint)

            scoreTextPaint.color = color
            canvas.drawText(
                "  $scoreText",
                tagLeft + 12f + textPaint.measureText(labelText),
                tagBottom - 8f,
                scoreTextPaint
            )
        }
    }

    private fun drawCornerBrackets(
        canvas: Canvas,
        l: Float, t: Float, r: Float, b: Float,
        len: Float
    ) {
        // วาด 8 เส้นในคราวเดียว — ไม่ต้องส่ง paint ซ้ำ
        canvas.drawLine(l,     t,     l + len, t,       cornerPaint)
        canvas.drawLine(l,     t,     l,       t + len, cornerPaint)
        canvas.drawLine(r,     t,     r - len, t,       cornerPaint)
        canvas.drawLine(r,     t,     r,       t + len, cornerPaint)
        canvas.drawLine(l,     b,     l + len, b,       cornerPaint)
        canvas.drawLine(l,     b,     l,       b - len, cornerPaint)
        canvas.drawLine(r,     b,     r - len, b,       cornerPaint)
        canvas.drawLine(r,     b,     r,       b - len, cornerPaint)
    }
}