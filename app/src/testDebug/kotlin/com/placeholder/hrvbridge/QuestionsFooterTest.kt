package com.placeholder.hrvbridge

import androidx.compose.ui.text.font.FontWeight
import com.placeholder.hrvbridge.ui.questionsFooter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuestionsFooterTest {
    @Test
    fun questionNamesAreBoldAndTextIsPlain() {
        val footer = questionsFooter(RuntimeEnvironment.getApplication().getString(R.string.questions_footer))
        assertEquals(
            "After each measurement, answer optional questions on soreness, fatigue, motivation, injury and sickness. They are uploaded with your values.",
            footer.text,
        )
        val bold = footer.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
            .map { footer.text.substring(it.start, it.end) }
        assertEquals(listOf("soreness", "fatigue", "motivation", "injury", "sickness"), bold)
    }
}
