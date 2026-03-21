package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.ParsedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TextPreprocessor — money parsing, date detection,
 * multi-transaction splitting, language detection.
 */
public class TextPreprocessorTest {

    private TextPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        preprocessor = new TextPreprocessor();
    }

    // ── Money Parsing Tests ──

    @ParameterizedTest
    @CsvSource({
            "'50k', 50000",
            "'30k', 30000",
            "'100k', 100000",
    })
    void testExtractAmount_KUnit(String input, long expected) {
        BigDecimal result = preprocessor.extractSingleAmount(input);
        assertNotNull(result, "Failed to extract: " + input);
        assertEquals(0, result.compareTo(new BigDecimal(expected)), "Mismatch for: " + input);
    }

    @ParameterizedTest
    @CsvSource({
            "'1tr', 1000000",
            "'1.2tr', 1200000",
            "'2 triệu', 2000000",
            "'1 triệu 5', 1500000",
    })
    void testExtractAmount_TrUnit(String input, long expected) {
        BigDecimal result = preprocessor.extractSingleAmount(input);
        assertNotNull(result, "Failed to extract: " + input);
        assertEquals(0, result.compareTo(new BigDecimal(expected)), "Mismatch for: " + input);
    }

    @ParameterizedTest
    @CsvSource({
            "'2 trăm', 200000",
            "'2 trăm rưỡi', 250000",
            "'3 trăm 5', 350000",
    })
    void testExtractAmount_TramUnit(String input, long expected) {
        BigDecimal result = preprocessor.extractSingleAmount(input);
        assertNotNull(result, "Failed to extract: " + input);
        assertEquals(0, result.compareTo(new BigDecimal(expected)), "Mismatch for: " + input);
    }

    @Test
    void testExtractAmount_Null() {
        assertNull(preprocessor.extractSingleAmount(null));
        assertNull(preprocessor.extractSingleAmount(""));
        assertNull(preprocessor.extractSingleAmount("   "));
    }

    @Test
    void testExtractAmount_ContextualSmallNumber() {
        // "ăn phở 45" → 45000 (food context multiplier)
        BigDecimal result = preprocessor.extractSingleAmount("ăn phở 45");
        assertNotNull(result);
        assertEquals(0, result.compareTo(new BigDecimal("45000")), "Food context multiplier failed");
    }

    @Test
    void testExtractAllAmounts_MultiTransaction() {
        List<BigDecimal> results = preprocessor.extractAllAmounts("ăn phở 45k, cà phê 30k");
        assertEquals(2, results.size());
        assertEquals(0, results.get(0).compareTo(new BigDecimal("45000")));
        assertEquals(0, results.get(1).compareTo(new BigDecimal("30000")));
    }

    // ── Text Normalization Tests ──

    @Test
    void testNormalize_VietnameseDiacritics() {
        assertEquals("an pho", preprocessor.normalizeVietnamese("ăn phở"));
        assertEquals("ca phe", preprocessor.normalizeVietnamese("cà phê"));
        assertEquals("xang xe", preprocessor.normalizeVietnamese("xăng xe"));
        assertEquals("do xang", preprocessor.normalizeVietnamese("đổ xăng"));
    }

    @Test
    void testNormalize_Empty() {
        assertEquals("", preprocessor.normalizeVietnamese(null));
        assertEquals("", preprocessor.normalizeVietnamese(""));
    }

    // ── Date Detection Tests ──

    @Test
    void testDetectDate_Today() {
        LocalDate[] range = preprocessor.detectDateRange("hom nay");
        assertEquals(LocalDate.now(), range[0]);
        assertEquals(LocalDate.now(), range[1]);
    }

    @Test
    void testDetectDate_Yesterday() {
        LocalDate[] range = preprocessor.detectDateRange("hom qua");
        assertEquals(LocalDate.now().minusDays(1), range[0]);
    }

    @Test
    void testDetectDate_ThisMonth() {
        LocalDate[] range = preprocessor.detectDateRange("thang nay");
        assertEquals(LocalDate.now().withDayOfMonth(1), range[0]);
        assertEquals(LocalDate.now(), range[1]);
    }

    @Test
    void testDetectDate_LastMonth() {
        LocalDate[] range = preprocessor.detectDateRange("thang truoc");
        LocalDate prev = LocalDate.now().minusMonths(1);
        assertEquals(prev.withDayOfMonth(1), range[0]);
        assertEquals(prev.withDayOfMonth(prev.lengthOfMonth()), range[1]);
    }

    @Test
    void testDetectDate_EnglishToday() {
        LocalDate[] range = preprocessor.detectDateRange("today");
        assertEquals(LocalDate.now(), range[0]);
    }

    @Test
    void testDetectDate_NoDate() {
        LocalDate[] range = preprocessor.detectDateRange("something random");
        assertNull(range[0]);
        assertNull(range[1]);
    }

    // ── Multi-Transaction Splitting Tests ──

    @Test
    void testSplit_MultipleTransactions() {
        List<String> parts = preprocessor.splitMultiTransaction("ăn phở 45k, cà phê 30k");
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).contains("phở"));
        assertTrue(parts.get(1).contains("phê"));
    }

    @Test
    void testSplit_SingleTransaction() {
        List<String> parts = preprocessor.splitMultiTransaction("ăn phở 45k");
        assertEquals(1, parts.size());
    }

    @Test
    void testSplit_EmptyString() {
        List<String> parts = preprocessor.splitMultiTransaction("");
        assertEquals(1, parts.size());
    }

    // ── Language Detection Tests ──

    @Test
    void testDetectLanguage_Vietnamese() {
        assertEquals("vi", preprocessor.detectLanguage(null, "ăn phở 45k"));
    }

    @Test
    void testDetectLanguage_English() {
        assertEquals("en", preprocessor.detectLanguage(null, "spent 45k today"));
    }

    @Test
    void testDetectLanguage_ExplicitVi() {
        assertEquals("vi", preprocessor.detectLanguage("vi", "any message"));
    }

    @Test
    void testDetectLanguage_ExplicitEn() {
        assertEquals("en", preprocessor.detectLanguage("en", "any message"));
    }

    // ── Full Preprocess Pipeline Test ──

    @Test
    void testPreprocess_FullPipeline() {
        ParsedMessage result = preprocessor.preprocess("sáng ăn phở 45k, cà phê 30k", null);
        assertNotNull(result);
        assertEquals("vi", result.getLanguage());
        assertTrue(result.hasAmounts());
        assertEquals(2, result.getExtractedAmounts().size());
        assertTrue(result.isMultiTransaction());
    }
}
