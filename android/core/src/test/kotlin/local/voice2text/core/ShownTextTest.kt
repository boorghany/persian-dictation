package local.voice2text.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShownTextTest {
    // 2026-10-27 06:03 on the Mac: Google showed the tail, then dropped it from the interim and the final.
    private val full = "به همین پرشین دیکتیشن علامت سوال الان یه چیز بی‌ربط"
    private val cut = "به همین پرشین دیکتیشن"

    @Test fun keepsTextWhenGoogleDropsWords() {
        val s = ShownText()
        assertEquals(full, s.interim(full))
        assertEquals(full, s.interim(cut))
        assertTrue(s.keeping)
        assertEquals(full, s.interim(""))
        assertEquals("$full و", s.interim("$full و"))
        assertFalse(s.keeping)
    }

    @Test fun smallRevisionsGoThrough() {
        val s = ShownText()
        s.interim("کارها انجام شدن")
        assertEquals("کارها انجام شدند", s.interim("کارها انجام شدند"))
        assertEquals("کارها انجام", s.interim("کارها انجام"))
    }

    @Test fun putsBackWordsTheFinalLeftOut() {
        val s = ShownText()
        s.interim(full)
        s.interim(cut)
        assertEquals(full to "علامت سوال الان یه چیز بی‌ربط", s.final(cut))
    }

    @Test fun finalWithRevisedLastWord() {
        val s = ShownText()
        s.interim("کارها انجام شدن و بعد رفتیم خونه")
        assertEquals("کارها انجام شدند و بعد رفتیم خونه" to "و بعد رفتیم خونه", s.final("کارها انجام شدند"))
    }

    @Test fun leavesDifferentOrNearlyCompleteFinalsAlone() {
        val s = ShownText()
        s.interim("یه کاری کن کارایی که از جنس تخصص هست")
        assertEquals("یه کاری کن به سجاد کارایی" to null, s.final("یه کاری کن به سجاد کارایی"))
        s.interim("سلام خوبی چطوری")
        assertEquals("سلام خوبی" to null, s.final("سلام خوبی")) // a single missing word may be noise
        s.interim("سلام")
        assertEquals("سلام خوبی چطوری" to null, s.final("سلام خوبی چطوری"))
    }

    @Test fun removesTheEchoFromTheNextUtterance() {
        val s = ShownText()
        s.interim(full)
        s.final(cut)
        assertEquals("", s.interim("علامت سوال"))
        assertEquals("", s.interim("علامت سوال الان یه چیز بی‌ربطیه"))
        assertEquals("و بعد", s.interim("علامت سوال الان یه چیز بی‌ربطیه و بعد"))
        assertEquals("و بعد" to null, s.final("علامت سوال الان یه چیز بی‌ربطیه و بعد"))
        // Only once.
        assertEquals("علامت سوال", s.interim("علامت سوال"))
    }

    @Test fun forgetsTheEchoWhenSomethingElseIsSaid() {
        val s = ShownText()
        s.interim(full)
        s.final(cut)
        assertEquals("", s.interim("علامت"))
        assertEquals("علامت تعجب", s.interim("علامت تعجب"))
        assertEquals("علامت تعجب" to null, s.final("علامت تعجب"))
    }

    @Test fun abandonKeepsWhatWasShown() {
        val s = ShownText()
        s.interim("ببین من دارم میگم")
        assertEquals("ببین من دارم میگم", s.abandon())
        assertEquals("", s.interim("ببین من"))
        s.interim("اوکی")
        assertNull(s.abandon())
    }
}

class ShownTextBoundaryTest {
    @Test fun differentLastWord() {
        val s = ShownText()
        s.interim("دیروز رفتیم خونه و خوابیدیم")
        assertEquals("دیروز رفتم خونه و خوابیدیم" to "خونه و خوابیدیم", s.final("دیروز رفتم"))
        s.interim("من می خوام برم بیرون")
        assertEquals("من می‌خوام برم بیرون" to "برم بیرون", s.final("من می‌خوام"))
        s.interim("الف ب ج د")
        assertEquals("الف ه ج د" to "ج د", s.final("الف ه"))
    }
}
