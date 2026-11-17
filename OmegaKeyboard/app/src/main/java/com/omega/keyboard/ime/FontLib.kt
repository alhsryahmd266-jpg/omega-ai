package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Typeface

/**
 * Real Arabic fonts (Ruq'ah, Naskh, Kufi, Nastaliq and more; all open-licence, see assets/fonts/OFL-licenses.txt) for what the keyboard
 * itself draws: the letters on the keys, the key bubble and the typing preview. A font is not text: it does not travel with a message,
 * so the app that receives the text shows it in its own font. Looks that do travel are the decorations of [com.omega.keyboard.core.Decor].
 */
object FontLib {
    /** [scale]: how big its letters are drawn compared with the phone's font (a tall face is drawn smaller on the keys) */
    class Font(val id: String, val label: String, val file: String, val scale: Float, val about: String)

    val fonts: List<Font> = listOf(
        Font("ruqaa", "رقعة", "ruqaa.ttf", 1.00f, "الرقعة الكلاسيكية"),
        Font("ruqaa_bold", "رقعة عريضة", "ruqaa_bold.ttf", 1.00f, "الرقعة بخط أسمك"),
        Font("rakkas", "رقعة الأفلام", "rakkas.ttf", 0.95f, "رقعة أفلام الخمسينات والستينات المصرية"),
        Font("amiri", "نسخ كلاسيكي", "amiri.ttf", 1.00f, "نسخ المطابع القديمة"),
        Font("naskh", "نسخ حديث", "naskh.ttf", 1.00f, "نسخ واضح للقراءة"),
        Font("katibeh", "قريب من الثلث", "katibeh.ttf", 1.05f, "نسخ فيه روح الثلث"),
        Font("mirza", "نسخ ميرزا", "mirza.ttf", 0.95f, "نسخ بحروف رفيعة"),
        Font("reemkufi", "كوفي", "reemkufi.ttf", 0.95f, "الكوفي الفاطمي"),
        Font("kufam", "كوفي حديث", "kufam.ttf", 0.90f, "كوفي عريض وحديث"),
        Font("nastaliq", "نستعليق", "nastaliq.ttf", 0.72f, "النستعليق المائل"),
        Font("elmessiri", "فرشاة", "elmessiri.ttf", 1.00f, "نسخ مرسوم بالفرشاة (المسيري)"),
        Font("cairo", "القاهرة", "cairo.ttf", 0.95f, "حديث ونظيف"),
        Font("lalezar", "لاليزار", "lalezar.ttf", 0.90f, "عريض للعناوين"),
        Font("vibes", "فايبس", "vibes.ttf", 1.00f, "خفيف ومرح"),
        Font("jomhuria", "جمهورية", "jomhuria.ttf", 1.25f, "عريض وضيق"),
        Font("playpen", "خط يد", "playpen.ttf", 0.90f, "كأنه مكتوب بالقلم"),
    )

    fun byId(id: String): Font? = fonts.firstOrNull { it.id == id }

    private val cache = HashMap<String, Typeface>()
    private val failed = HashSet<String>()

    /** the font's typeface, or null when [id] is empty or unknown (the phone's own font is then used) */
    @Synchronized
    fun face(ctx: Context, id: String): Typeface? {
        if (id.isEmpty()) return null
        cache[id]?.let { return it }
        if (id in failed) return null
        val f = byId(id) ?: return null
        return try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/" + f.file).also { cache[id] = it }
        } catch (_: Throwable) {
            failed.add(id)
            null
        }
    }
}
