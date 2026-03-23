package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.IntentResult;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.dto.ParsedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IntentDetector — rule-based intent classification.
 */
public class IntentDetectorTest {

    private IntentDetector detector;
    private TextPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        detector = new IntentDetector();
        preprocessor = new TextPreprocessor();
    }

    private ParsedMessage parse(String message) {
        return preprocessor.preprocess(message, null);
    }

    @Test
    void testDeleteIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("xóa giao dịch 45k"));
        assertEquals(Intent.DELETE_TRANSACTION, result.getIntent());
        assertTrue(result.getConfidence() >= 0.8);
    }

    @Test
    void testDeleteIntent_English() {
        IntentResult result = detector.detect(parse("delete the 45k transaction"));
        assertEquals(Intent.DELETE_TRANSACTION, result.getIntent());
    }

    @Test
    void testUpdateIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("sửa khoản xăng thành 60k"));
        assertEquals(Intent.UPDATE_TRANSACTION, result.getIntent());
    }

    @Test
    void testUpdateIntent_English() {
        IntentResult result = detector.detect(parse("change lunch to 50k"));
        assertEquals(Intent.UPDATE_TRANSACTION, result.getIntent());
    }

    @Test
    void testQueryIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("hôm nay tiêu bao nhiêu"));
        assertEquals(Intent.QUERY_TRANSACTION, result.getIntent());
    }

    @Test
    void testQueryIntent_English() {
        IntentResult result = detector.detect(parse("how much did I spend this month"));
        assertEquals(Intent.QUERY_TRANSACTION, result.getIntent());
    }

    @Test
    void testQueryIntent_TopCategory() {
        IntentResult result = detector.detect(parse("tháng này tiêu nhiều nhất vào cái gì"));
        assertEquals(Intent.QUERY_TRANSACTION, result.getIntent());
    }

    @Test
    void testInsertIntent_WithAmount() {
        IntentResult result = detector.detect(parse("ăn phở 45k"));
        assertEquals(Intent.INSERT_TRANSACTION, result.getIntent());
    }

    @Test
    void testAdviceIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("làm sao tiết kiệm tiền"));
        assertEquals(Intent.FINANCIAL_ADVICE, result.getIntent());
    }

    @Test
    void testAdviceIntent_English() {
        IntentResult result = detector.detect(parse("how to manage money"));
        assertEquals(Intent.FINANCIAL_ADVICE, result.getIntent());
    }

    @Test
    void testUnknownIntent_NoAmount() {
        IntentResult result = detector.detect(parse("xin chào"));
        assertEquals(Intent.UNKNOWN, result.getIntent());
        assertTrue(result.getConfidence() < 0.5);
    }

    @Test
    void testDeleteTakesPriority_OverQuery() {
        // "xóa" should be DELETE, not QUERY, even if "bao nhiêu" is missing
        IntentResult result = detector.detect(parse("xóa khoản ăn phở"));
        assertEquals(Intent.DELETE_TRANSACTION, result.getIntent());
    }

    @Test
    void testDeleteDoesNotOverride_QueryAboutDeletion() {
        // "xóa bao nhiêu" is a query about how many were deleted
        IntentResult result = detector.detect(parse("xóa bao nhiêu giao dịch rồi"));
        // Contains "xoa" but also "bao nhieu" → should be QUERY
        assertEquals(Intent.QUERY_TRANSACTION, result.getIntent());
    }

    @Test
    void testBudgetIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("xem kế hoạch tài chính"));
        assertEquals(Intent.VIEW_FINANCIAL_PLAN, result.getIntent());
        assertTrue(result.getConfidence() >= 0.8);
    }

    @Test
    void testBudgetIntent_English() {
        IntentResult result = detector.detect(parse("show my financial plan"));
        assertEquals(Intent.VIEW_FINANCIAL_PLAN, result.getIntent());
    }

    @Test
    void testMonthlySummaryIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("tóm tắt tháng này"));
        assertEquals(Intent.MONTHLY_SUMMARY, result.getIntent());
    }

    @Test
    void testMonthlySummaryIntent_English() {
        IntentResult result = detector.detect(parse("monthly summary report"));
        assertEquals(Intent.MONTHLY_SUMMARY, result.getIntent());
    }

    @Test
    void testFinancialScoreIntent_Vietnamese() {
        IntentResult result = detector.detect(parse("chấm điểm tài chính"));
        assertEquals(Intent.FINANCIAL_SCORE, result.getIntent());
        assertTrue(result.getConfidence() >= 0.8);
    }

    @Test
    void testFinancialScoreIntent_English() {
        IntentResult result = detector.detect(parse("what is my financial score"));
        assertEquals(Intent.FINANCIAL_SCORE, result.getIntent());
    }
}
