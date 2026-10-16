package local.voice2text.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpokenPunctuationTest {
    private val cases = listOf(
        "سلام نقطه امروز هوا خوبه" to "سلام. امروز هوا خوبه",
        "حالت خوبه علامت سوال" to "حالت خوبه؟",
        "حالت خوبه علامت سؤال" to "حالت خوبه؟",
        "سیب ویرگول پرتقال ویرگول موز" to "سیب، پرتقال، موز",
        "خط اول خط بعد خط دوم" to "خط اول\nخط دوم",
        "نقطه" to ".",
        "خط بعد" to "\n",
        "این نقطه‌ی عطف بود" to "این نقطه‌ی عطف بود",
        "عدد 3.5 است نقطه" to "عدد 3.5 است.",
        "خط اول خط بد خط دوم" to "خط اول\nخط دوم",
        "موارد زیر دو نقطه سیب" to "موارد زیر: سیب",
        "موارد زیر دونقطه سیب" to "موارد زیر: سیب",
        "ساعت 10:30 است" to "ساعت 10:30 است",
        "دو تا سیب" to "دو تا سیب",
        "دو نقطه‌ی مهم" to "دو نقطه‌ی مهم",
        "این پرانتز باز مثلاً پرانتز بسته خوبه" to "این (مثلاً) خوبه",
        "پرانتز باز سلام" to "(سلام",
        "سلام پرانتز بسته نقطه" to "سلام).",
        "سلام پرانتز باز" to "سلام (",
        "پرانتز بسته" to ")",
        "پاراگراف اول پاراگراف بعد پاراگراف دوم" to "پاراگراف اول\n\nپاراگراف دوم",
        "تموم شد نقطه پاراگراف بد سلام" to "تموم شد.\n\nسلام",
    )

    @Test fun persianCommands() {
        for ((input, expected) in cases) assertEquals(expected, SpokenPunctuation.apply(input, "fa-IR"), input)
    }

    @Test fun englishUntouched() = assertEquals("hello period", SpokenPunctuation.apply("hello period", "en-US"))
}

class AttachesToNextTest {
    @Test fun cases() {
        assertEquals(true, SpokenPunctuation.attachesToNext("سلام ("))
        assertEquals(true, SpokenPunctuation.attachesToNext("سلام\n"))
        assertEquals(false, SpokenPunctuation.attachesToNext("سلام)"))
    }
}

class CorrectionsTest {
    @Test fun persian() {
        assertEquals("سلام ارشیا", Corrections.apply("سلام عرشیا", "fa-IR"))
        assertEquals("ارشیا. خوبی", SpokenPunctuation.apply(Corrections.apply("عرشیا نقطه خوبی", "fa-IR"), "fa-IR"))
        assertEquals("عرشیاها", Corrections.apply("عرشیاها", "fa-IR")) // whole words only
        assertEquals("طول و عرض جدول", Corrections.apply("طول و ارز جدول", "fa-IR"))
        assertEquals("نوار عرضی", Corrections.apply("نوار ارزی", "fa-IR"))
        assertEquals("ارزش", Corrections.apply("ارزش", "fa-IR"))
        assertEquals("آقای بورقانی", Corrections.apply("آقای بورغانی", "fa-IR"))
    }

    @Test fun englishUntouched() = assertEquals("عرشیا", Corrections.apply("عرشیا", "en-US"))
}

class PrefixDropRepairTest {
    private fun run(interims: List<String>, final: String): String {
        val r = PrefixDropRepair()
        interims.forEach { r.interim(it) }
        return r.final(final)
    }

    private val cases = listOf(
        Triple(listOf("توی لاجیک", "توی لاجیک برنامه"), "توی اجیک برنامه", "توی لاجیک برنامه"),
        Triple(listOf("یه باگی داره این ای پی آی"), "یه باگی داره ین ای پی آی", "یه باگی داره این ای پی آی"),
        Triple(listOf("خیلی موقع‌ها"), "یلی موقع‌ها", "خیلی موقع‌ها"),
        Triple(listOf("خیلی موقع‌ها", "یلی موقع‌ها میاد"), "یلی موقع‌ها میاد", "خیلی موقع‌ها میاد"),
        Triple(listOf("من کتاب خوندم"), "تو تاب بازی کردی", "تو تاب بازی کردی"),
        Triple(listOf("کتابها رو"), "کتاب رو", "کتاب رو"),
        Triple(listOf("دانشگاه رفتم"), "شگاه رفتم", "شگاه رفتم"),
        Triple(listOf("صبح علی آقا", "صبح لی آقا", "صبح ی آقا"), "صبح ی آقا", "صبح علی آقا"),
        Triple(listOf("علی آقا احیاناً", "لی آقا احیاناً جمعه"), "ی آقا احیاناً جمعه صبح", "علی آقا احیاناً جمعه صبح"),
        Triple(listOf("میتونی ترابل"), "ی‌تونی ترابل", "میتونی ترابل"),
        Triple(listOf("می‌تونی ترابل"), "ی‌تونی ترابل", "می‌تونی ترابل"),
        Triple(emptyList(), "سلام دنیا", "سلام دنیا"),
    )

    @Test fun repairs() {
        for ((interims, final, expected) in cases) assertEquals(expected, run(interims, final), final)
    }
}

class AudioBufferTest {
    @Test fun keepsNewestBytes() {
        val b = AudioBuffer(5)
        b.append(byteArrayOf(1, 2, 3))
        b.append(byteArrayOf(4, 5, 6, 7))
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7), b.toByteArray())
        repeat(10) { b.append(byteArrayOf(it.toByte())) }
        assertContentEquals(byteArrayOf(5, 6, 7, 8, 9), b.toByteArray())
        b.append(ByteArray(12) { it.toByte() })
        assertContentEquals(byteArrayOf(7, 8, 9, 10, 11), b.toByteArray())
        b.clear()
        assertContentEquals(byteArrayOf(), b.toByteArray())
    }
}
