package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.ParsedMessage;
import com.example.finance_backend.dto.TransactionSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EntityExtractor — category inference, transaction type, query params.
 */
public class EntityExtractorTest {

    private EntityExtractor extractor;
    private TextPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        preprocessor = new TextPreprocessor();
        extractor = new EntityExtractor(preprocessor);
    }

    // ── Category Inference Tests ──

    @Test
    void testInferCategory_Food() {
        assertEquals("Ăn uống", extractor.inferCategory("an pho 45k"));
        assertEquals("Ăn uống", extractor.inferCategory("ca phe sang"));
        assertEquals("Ăn uống", extractor.inferCategory("coffee morning"));
    }

    @Test
    void testInferCategory_Transport() {
        assertEquals("Xăng xe", extractor.inferCategory("do xang 50k"));
        assertEquals("Xăng xe", extractor.inferCategory("grab 30k"));
        assertEquals("Xăng xe", extractor.inferCategory("taxi home"));
    }

    @Test
    void testInferCategory_Shopping() {
        assertEquals("Mua sắm", extractor.inferCategory("mua giay"));
        assertEquals("Mua sắm", extractor.inferCategory("shopee 100k"));
        assertEquals("Mua sắm", extractor.inferCategory("groceries 200k"));
    }

    @Test
    void testInferCategory_Health() {
        assertEquals("Y tế", extractor.inferCategory("kham benh"));
        assertEquals("Y tế", extractor.inferCategory("medicine 50k"));
    }

    @Test
    void testInferCategory_Unknown() {
        assertNull(extractor.inferCategory("something random"));
    }

    // ── Transaction Type Tests ──

    @Test
    void testTransactionType_Expense() {
        assertEquals("EXPENSE", extractor.inferTransactionType("an pho 45k"));
        assertEquals("EXPENSE", extractor.inferTransactionType("mua sach"));
    }

    @Test
    void testTransactionType_Income() {
        assertEquals("INCOME", extractor.inferTransactionType("nhan luong"));
        assertEquals("INCOME", extractor.inferTransactionType("salary deposit"));
    }

    // ── Query Metric Detection ──

    @Test
    void testDetectMetric() {
        assertEquals("TOP_CATEGORY", extractor.detectMetric("nhieu nhat"));
        assertEquals("LIST", extractor.detectMetric("danh sach giao dich"));
        assertEquals("AVERAGE", extractor.detectMetric("trung binh moi ngay"));
        assertEquals("TREND", extractor.detectMetric("tang hay giam"));
        assertEquals("PERCENTAGE", extractor.detectMetric("ty le chi tieu"));
        assertEquals("TOTAL", extractor.detectMetric("tong chi tieu"));
    }

    // ── Category Normalization ──

    @Test
    void testNormalizeCategoryName() {
        assertEquals("Ăn uống", extractor.normalizeCategoryName("Food"));
        assertEquals("Ăn uống", extractor.normalizeCategoryName("lunch"));
        assertEquals("Xăng xe", extractor.normalizeCategoryName("Gas"));
        assertEquals("Mua sắm", extractor.normalizeCategoryName("Shopping"));
        assertEquals("Nạp tiền", extractor.normalizeCategoryName("Income"));
        assertEquals("Khác", extractor.normalizeCategoryName("Other"));
    }

    // ── Slot Extraction Tests ──

    @Test
    void testExtractSlots_SingleTransaction() {
        ParsedMessage parsed = preprocessor.preprocess("ăn phở 45k", null);
        List<TransactionSlot> slots = extractor.extractTransactionSlots(parsed);
        assertEquals(1, slots.size());
        assertNotNull(slots.get(0).getAmount());
        assertEquals("Ăn uống", slots.get(0).getCategoryName());
    }

    @Test
    void testExtractSlots_MultiTransaction() {
        ParsedMessage parsed = preprocessor.preprocess("ăn phở 45k, cà phê 30k", null);
        List<TransactionSlot> slots = extractor.extractTransactionSlots(parsed);
        assertEquals(2, slots.size());
    }

    @Test
    void testExtractSlots_MissingAmount() {
        ParsedMessage parsed = preprocessor.preprocess("ăn trưa", null);
        List<TransactionSlot> slots = extractor.extractTransactionSlots(parsed);
        assertEquals(1, slots.size());
        assertTrue(slots.get(0).isMissingCriticalInfo());
    }

    // ── Repeat Parsing Tests ──

    @Test
    void testInferRepeatType() {
        assertEquals("MONTHLY", extractor.inferRepeatType("moi thang"));
        assertEquals("WEEKLY", extractor.inferRepeatType("hang tuan"));
        assertEquals("DAILY", extractor.inferRepeatType("moi ngay"));
        assertEquals("YEARLY", extractor.inferRepeatType("hang nam"));
        assertEquals("CUSTOM", extractor.inferRepeatType("dinh ky"));
        assertEquals("NONE", extractor.inferRepeatType("an pho"));
    }

    @Test
    void testInferRepeatConfig_Monthly() {
        // "ngay 5 hang thang" -> {"day_of_month": 5}
        String config = extractor.inferRepeatConfig("tra tien dien ngay 5 hang thang", "MONTHLY");
        assertEquals("{\"day_of_month\": 5}", config);
    }

    @Test
    void testInferRepeatConfig_Weekly() {
        // "thu 2 va thu 4 hang tuan" -> {"day_of_week": [2, 4]}
        String config = extractor.inferRepeatConfig("di gym thu 2 va thu 4 hang tuan", "WEEKLY");
        assertEquals("{\"day_of_week\": [2, 4]}", config);
    }
}
