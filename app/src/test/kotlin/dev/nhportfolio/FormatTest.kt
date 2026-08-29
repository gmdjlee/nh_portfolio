package dev.nhportfolio

import dev.nhportfolio.api.NhException
import dev.nhportfolio.security.VaultCorruptException
import dev.nhportfolio.ui.bpPct
import dev.nhportfolio.ui.krw
import dev.nhportfolio.ui.pct
import dev.nhportfolio.ui.shares
import dev.nhportfolio.ui.userMessage
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatTest {
    private val original = Locale.getDefault()

    @BeforeTest
    fun useForeignLocale() {
        Locale.setDefault(Locale.GERMANY) // 기기 로케일이 달라도 결과가 같아야 한다
    }

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun `금액과 수량은 천 단위로 끊는다`() {
        assertEquals("1,234,567", 1_234_567L.krw())
        assertEquals("0", 0L.krw())
        assertEquals("-1,000", (-1_000L).krw())
        assertEquals("12,345", 12_345L.shares())
    }

    @Test
    fun `basis point 는 퍼센트로 보인다`() {
        assertEquals("12.50%", 1_250.bpPct())
        assertEquals("100.00%", 10_000.bpPct())
        assertEquals("0.00%", 0.bpPct())
    }

    @Test
    fun `수익률은 부호를 항상 붙인다`() {
        assertEquals("+2.94%", 2.94.pct())
        assertEquals("-3.10%", (-3.1).pct())
        assertEquals("+0.00%", 0.0.pct())
    }

    @Test
    fun `사용자 메시지는 비밀을 담지 않고 원인별로 다르다`() {
        assertTrue("앱 키" in NhException("AUTH", "no appkey").userMessage())
        assertTrue("앱 키" in NhException("HTTP401", "token rejected").userMessage())
        assertTrue("요청이 많" in NhException("HTTP429", "x").userMessage())
        assertEquals("종목코드 항목을 입력하세요.", NhException("40010", "종목코드 항목을 입력하세요.").userMessage())
        assertEquals("네트워크 오류", IOException("https://...?appkey=SECRET").userMessage())
        assertEquals("응답 형식 오류", SerializationException("field APPSECRET missing").userMessage())
        assertTrue("손상" in VaultCorruptException().userMessage())
        assertEquals("", IllegalStateException("locked").userMessage())
    }
}
