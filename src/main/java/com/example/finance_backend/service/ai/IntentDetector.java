package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.IntentResult;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.dto.IntentResult.Source;
import com.example.finance_backend.dto.ParsedMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Two-phase intent detector: rule-based first (fast, no API cost),
 * Gemini fallback when rules can't determine intent.
 */
@Component
public class IntentDetector {

    // ── DELETE keywords ──
    private static final List<String> DELETE_KW = List.of(
            "xoa", "huy", "bo qua", "delete", "remove", "erase", "clear", "cancel");

    // ── UPDATE keywords ──
    private static final List<String> UPDATE_KW = List.of(
            "sua", "doi", "cap nhat", "thanh", "update", "change", "edit", "set", "replace");

    // ── QUERY keywords ──
    private static final List<String> QUERY_KW = List.of(
            "bao nhieu", "tong", "thong ke", "nhieu nhat", "cao nhat",
            "danh sach", "liet ke", "trung binh",
            "how much", "total", "summary", "most", "highest", "list", "show",
            "average", "trend", "percent", "percentage", "ratio");

    // ── Time period keywords that strengthen QUERY intent ──
    private static final List<String> TIME_QUERY_KW = List.of(
            "thang nay", "thang truoc", "hom nay", "hom qua", "nam nay",
            "tuan nay", "tuan truoc",
            "this month", "last month", "today", "yesterday", "this year",
            "last year", "this week", "last week");

    // ── ADVICE keywords ──
    private static final List<String> ADVICE_KW = List.of(
            "lam sao", "cach", "tu van", "goi y", "khuyen",
            "tiet kiem", "quan ly",
            "how to", "advice", "suggest", "recommend", "tips", "save money",
            "manage", "financial");

    // ── CREATE BUDGET keywords ──
    private static final List<String> CREATE_BUDGET_KW = List.of(
            "tao ngan sach", "lap ngan sach", "dat ngan sach", "dat han muc", "gioi han chi tieu",
            "tao han muc", "set ngan sach", "budget cho", "ngan sach cho", "muon dat ngan sach", 
            "giup toi tao ngan sach", "ngan sach", "han muc", "budget");

    // ── CREATE INCOME GOAL keywords ──
    private static final List<String> CREATE_INCOME_GOAL_KW = List.of(
            "tao muc tieu", "dat muc tieu", "lap muc tieu", "muc tieu thu", "muc tieu thu nhap",
            "muc tieu tai chinh", "muc tieu kiem tien", "muon dat muc tieu", "giup toi tao muc tieu",
            "muc tieu luong", "freelance", "muc tieu kiem", "thu nhap", "muc tieu", "income goal");

    // ── VIEW FINANCIAL PLAN keywords ──
    private static final List<String> VIEW_PLAN_KW = List.of(
            "xem ke hoach tai chinh", "ke hoach tai chinh", "ke hoach tai chinh cua toi", "xem ngan sach", 
            "xem muc tieu", "thong ke ke hoach", "tong quan tai chinh", "financial plan", "show plan");

    // ── FINANCIAL SCORE keywords ──
    private static final List<String> SCORE_KW = List.of(
            "diem", "score", "cham diem", "danh gia", "xep hang",
            "financial score", "diem tai chinh", "suc khoe tai chinh");

    // ── INSIGHT / SUMMARY keywords ──
    private static final List<String> INSIGHT_KW = List.of(
            "phan tich", "tom tat", "bao cao", "suc khoe",
            "summary", "report", "insight", "analysis", "health",
            "tong ket", "monthly");

    // ── CONFIRM keywords (for draft confirmation) ──
    private static final List<String> CONFIRM_KW = List.of(
            "ok", "luu", "dung", "dong y", "chinh xac", "duyet", "xac nhan",
            "co", "vang", "u tru", "chuan", "uy", "chot", 
            "yes", "save", "confirm", "approve", "correct", "yep", "yeah");

    /**
     * Detect intent using rule-based keyword matching.
     * Returns IntentResult with confidence score.
     */
    public IntentResult detect(ParsedMessage parsed) {
        String normalized = parsed.getNormalizedText();
        if (normalized == null || normalized.isBlank()) {
            return IntentResult.builder()
                    .intent(Intent.UNKNOWN)
                    .confidence(0.0)
                    .source(Source.RULE)
                    .build();
        }

        boolean hasAmount = parsed.hasAmounts();

        // Phase 1: Check DELETE (highest priority when keyword is present)
        if (matchesKeywords(normalized, DELETE_KW)
                && !normalized.contains("bao nhieu")
                && !normalized.contains("how much")
                && !normalized.contains("how many")) {
            return IntentResult.builder()
                    .intent(Intent.DELETE_TRANSACTION)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 2: Check UPDATE
        if (matchesKeywords(normalized, UPDATE_KW) && !matchesKeywords(normalized, DELETE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.UPDATE_TRANSACTION)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 3: Check FINANCIAL SCORE
        if (matchesKeywords(normalized, SCORE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.FINANCIAL_SCORE)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 4: Check VIEW FINANCIAL PLAN
        if (matchesKeywords(normalized, VIEW_PLAN_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.VIEW_FINANCIAL_PLAN)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 4.5: Check INCOME TARGET (before BUDGET to avoid conflict)
        if (matchesKeywords(normalized, CREATE_INCOME_GOAL_KW)) {
            // Because words like "muc tieu" are broad, check context
            return IntentResult.builder()
                    .intent(Intent.CREATE_INCOME_GOAL)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 5: Check BUDGET
        if (matchesKeywords(normalized, CREATE_BUDGET_KW)) {
            return IntentResult.builder()
                    .intent(Intent.CREATE_BUDGET)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 5: Check MONTHLY SUMMARY / INSIGHT
        if (matchesKeywords(normalized, INSIGHT_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.MONTHLY_SUMMARY)
                    .confidence(0.8)
                    .source(Source.RULE)
                    .build();
        }
        
        // Phase 5.5: Check CONFIRM (for drafts)
        if (matchesKeywords(normalized, CONFIRM_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 6: Check QUERY
        boolean isQuery = matchesKeywords(normalized, QUERY_KW);
        boolean isTimeQuery = matchesKeywords(normalized, TIME_QUERY_KW);
        if (isQuery || (isTimeQuery && !hasAmount)) {
            double confidence = isQuery ? 0.85 : 0.65;
            return IntentResult.builder()
                    .intent(Intent.QUERY_TRANSACTION)
                    .confidence(confidence)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 7: Check ADVICE
        if (matchesKeywords(normalized, ADVICE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.FINANCIAL_ADVICE)
                    .confidence(0.8)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 8: INSERT if there's an amount
        if (hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.75)
                    .source(Source.RULE)
                    .build();
        }

        // Phase 9: Low-confidence — might be INSERT with missing amount, or general chat
        return IntentResult.builder()
                .intent(Intent.UNKNOWN)
                .confidence(0.3)
                .source(Source.RULE)
                .build();
    }

    /**
     * Checks if any of the keywords appear in the text.
     */
    private boolean matchesKeywords(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
