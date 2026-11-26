package com.omega.sight.core

import kotlin.math.min

/**
 * The "brain" file: an optional TensorFlow Lite model the owner trains himself. It looks at a short list of numbers about the targets and
 * the camera and says where the crosshair should go. The app always has its own classical controller; the brain only changes
 * the aim point, and a slider mixes the two (0 = the classical one alone).
 */
object BrainSpec {
    /** how many targets are given to the brain, nearest to the crosshair first */
    const val K = 4

    /** numbers per target */
    const val PER = 8

    /** numbers about the situation */
    const val OWN = 8
    const val IN = K * PER + OWN

    /** what the owner sees in the app under "صيغة ملف العقل" */
    fun describe(): String = """
ملف العقل (اختياري): TensorFlow Lite ‏(.tflite)

الدخل: float32 بشكل [1, $IN]
أولاً $K أهداف × $PER أرقام (الأقرب للمؤشر أولاً، والناقص أصفار):
  0  موجود (1)
  1  dx = (نقطة التصويب - المؤشر) أفقياً
  2  dy = (نقطة التصويب - المؤشر) رأسياً
  3  عرض الهدف
  4  ارتفاع الهدف
  5  سرعة الهدف أفقياً (في الثانية)
  6  سرعة الهدف رأسياً (في الثانية)
  7  النوع: 0 = شيء يتحرك، 0.5 = جسم، 0.75 = وهج أحمر، 1 = رأس
كل المسافات والسرعات بوحدة «ارتفاع الصورة» (1.0 = ارتفاع الشاشة)، والقيم بين -2 و 2.
ثم $OWN أرقام عن الحالة:
  0  سرعة دوران الكاميرا أفقياً (ارتفاع الصورة في الثانية)
  1  سرعة دوران الكاميرا رأسياً
  2  سرعة التقريب (المشي للأمام) في الثانية
  3  مدة الضغط على الإطلاق ÷ 3 (بين 0 و 1)
  4  آخر حركة للإصبع أفقياً (ارتفاع الشاشة)
  5  آخر حركة للإصبع رأسياً
  6  عدد الأهداف ÷ $K
  7  1.0 ثابت

الخرج: float32 بشكل [1, 3] أو [1, 2]
  0  dx: أين يجب أن يتحرك المؤشر أفقياً (ارتفاع الصورة، موجب = يمين)
  1  dy: رأسياً (موجب = لتحت)
  2  engage من 0 إلى 1 (من 0.5 فأكثر = يصوّب). لو الخرج [1, 2] يعتبر engage = 1.
""".trimIndent()
}

/** What the brain answered, in analysis-frame pixels. */
class BrainOut(val dx: Float, val dy: Float, val engage: Float)

object Brain {
    /**
     * Fills [out] (size [BrainSpec.IN]) from the followed targets.
     * @param lastDx the finger's last movement in screen pixels
     * @param screenH the screen height in pixels
     */
    fun buildState(
        tracks: List<Track>,
        picker: TargetPicker,
        cross: Vec2,
        frameH: Float,
        ego: Ego,
        dt: Float,
        firingSeconds: Float,
        lastDx: Float,
        lastDy: Float,
        screenH: Float,
        headBias: Float,
        out: FloatArray,
    ) {
        java.util.Arrays.fill(out, 0f)
        val dts = dt.coerceIn(0.005f, 0.25f)
        val sorted = tracks.sortedBy {
            val a = picker.aimPoint(it, headBias)
            (a.x - cross.x) * (a.x - cross.x) + (a.y - cross.y) * (a.y - cross.y)
        }
        for (i in 0 until min(BrainSpec.K, sorted.size)) {
            val t = sorted[i]
            val a = picker.aimPoint(t, headBias)
            val o = i * BrainSpec.PER
            out[o] = 1f
            out[o + 1] = clamp((a.x - cross.x) / frameH, 2f)
            out[o + 2] = clamp((a.y - cross.y) / frameH, 2f)
            out[o + 3] = clamp(t.box.w / frameH, 2f)
            out[o + 4] = clamp(t.box.h / frameH, 2f)
            out[o + 5] = clamp(t.vx / frameH, 2f)
            out[o + 6] = clamp(t.vy / frameH, 2f)
            out[o + 7] = when (t.kind) {
                Kind.MOTION -> 0f
                Kind.BODY -> 0.5f
                Kind.GLOW -> 0.75f
                Kind.HEAD -> 1f
            }
        }
        val o = BrainSpec.K * BrainSpec.PER
        out[o] = clamp(ego.tx / frameH / dts, 4f)
        out[o + 1] = clamp(ego.ty / frameH / dts, 4f)
        out[o + 2] = clamp(ego.s / dts, 2f)
        out[o + 3] = (firingSeconds / 3f).coerceIn(0f, 1f)
        out[o + 4] = clamp(lastDx / screenH, 1f)
        out[o + 5] = clamp(lastDy / screenH, 1f)
        out[o + 6] = sorted.size.coerceAtMost(BrainSpec.K) / BrainSpec.K.toFloat()
        out[o + 7] = 1f
    }

    /** reads the brain's answer: 2 or 3 numbers, null when the shape is not one of those or a value is not a number */
    fun interpret(out: FloatArray, frameH: Float): BrainOut? {
        if (out.size != 2 && out.size != 3) return null
        for (v in out) if (v.isNaN() || v.isInfinite()) return null
        val engage = if (out.size == 3) out[2].coerceIn(0f, 1f) else 1f
        return BrainOut(out[0].coerceIn(-2f, 2f) * frameH, out[1].coerceIn(-2f, 2f) * frameH, engage)
    }

    /** the aim point after mixing: 0 = the classical one, 1 = where the brain wants the crosshair */
    fun mix(classical: Vec2, cross: Vec2, brain: BrainOut, amount: Float): Vec2 {
        if (brain.engage < 0.5f) return classical
        val a = amount.coerceIn(0f, 1f)
        val bx = cross.x + brain.dx
        val by = cross.y + brain.dy
        return Vec2(classical.x + a * (bx - classical.x), classical.y + a * (by - classical.y))
    }

    private fun clamp(v: Float, lim: Float) = v.coerceIn(-lim, lim)
}
