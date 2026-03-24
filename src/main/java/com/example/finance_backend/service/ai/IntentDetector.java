package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.IntentResult;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.dto.IntentResult.Source;
import com.example.finance_backend.dto.ParsedMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trình phát hiện ý định (intent) hai giai đoạn: dựa trên quy tắc trước (nhanh, không tốn chi phí API),
 * dự phòng Gemini khi các quy tắc không thể xác định được ý định.
 */
@Component
public class IntentDetector {

    // ── từ khóa XÓA ──
    private static final List<String> DELETE_KW = List.of(
            "xoa", "huy", "bo qua", "delete", "remove", "erase", "clear", "cancel");

    // ── từ khóa CẬP NHẬT ──
    private static final List<String> UPDATE_KW = List.of(
            "sua", "doi", "cap nhat", "thanh", "update", "change", "edit", "set", "replace");

    // ── từ khóa TRUY VẤN ──
    private static final List<String> QUERY_KW = List.of(
            "bao nhieu", "tong", "thong ke", "nhieu nhat", "cao nhat",
            "danh sach", "liet ke", "trung binh",
            "how much", "total", "summary", "most", "highest", "list", "show",
            "average", "trend", "percent", "percentage", "ratio");

    // ── Từ khóa khoảng thời gian giúp củng cố ý định TRUY VẤN ──
    private static final List<String> TIME_QUERY_KW = List.of(
            "thang nay", "thang truoc", "hom nay", "hom qua", "nam nay",
            "tuan nay", "tuan truoc",
            "this month", "last month", "today", "yesterday", "this year",
            "last year", "this week", "last week");

    // ── từ khóa LỜI KHUYÊN ──
    private static final List<String> ADVICE_KW = List.of(
            "lam sao", "cach", "tu van", "goi y", "khuyen",
            "tiet kiem", "quan ly",
            "how to", "advice", "suggest", "recommend", "tips", "save money",
            "manage", "financial");

    // ── từ khóa TẠO NGÂN SÁCH ──
    private static final List<String> CREATE_BUDGET_KW = List.of(
            "tao ngan sach", "lap ngan sach", "dat ngan sach", "dat han muc", "gioi han chi tieu",
            "tao han muc", "set ngan sach", "budget cho", "ngan sach cho", "muon dat ngan sach", 
            "giup toi tao ngan sach", "ngan sach", "han muc", "budget");

    // ── từ khóa TẠO MỤC TIÊU THU NHẬP ──
    private static final List<String> CREATE_INCOME_GOAL_KW = List.of(
            "tao muc tieu", "dat muc tieu", "lap muc tieu", "muc tieu thu", "muc tieu thu nhap",
            "muc tieu tai chinh", "muc tieu kiem tien", "muon dat muc tieu", "giup toi tao muc tieu",
            "muc tieu luong", "freelance", "muc tieu kiem", "thu nhap", "muc tieu", "income goal");

    // ── từ khóa XEM KẾ HOẠCH TÀI CHÍNH ──
    private static final List<String> VIEW_PLAN_KW = List.of(
            "xem ke hoach tai chinh", "ke hoach tai chinh", "ke hoach tai chinh cua toi", "xem ngan sach", 
            "xem muc tieu", "thong ke ke hoach", "tong quan tai chinh", "financial plan", "show plan");

    // ── từ khóa ĐIỂM TÀI CHÍNH ──
    private static final List<String> SCORE_KW = List.of(
            "diem", "score", "cham diem", "danh gia", "xep hang",
            "financial score", "diem tai chinh", "suc khoe tai chinh");

    // ── từ khóa THÔNG TIN CHI SÂU / TÓM TẮT ──
    private static final List<String> INSIGHT_KW = List.of(
            "phan tich", "tom tat", "bao cao", "suc khoe",
            "summary", "report", "insight", "analysis", "health",
            "tong ket", "monthly");

    // ── từ khóa XÁC NHẬN (để xác nhận bản nháp) ──
    private static final List<String> CONFIRM_KW = List.of(
            "ok", "luu", "dung", "dong y", "chinh xac", "duyet", "xac nhan",
            "co", "vang", "u tru", "chuan", "uy", "chot", 
            "yes", "save", "confirm", "approve", "correct", "yep", "yeah");

    /**
     * Phát hiện ý định bằng cách khớp từ khóa dựa trên quy tắc.
     * Trả về IntentResult với điểm tin cậy.
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

        // Giai đoạn 1: Kiểm tra XÓA (ưu tiên cao nhất khi có từ khóa)
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

        // Giai đoạn 2: Kiểm tra CẬP NHẬT
        if (matchesKeywords(normalized, UPDATE_KW) && !matchesKeywords(normalized, DELETE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.UPDATE_TRANSACTION)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 3: Kiểm tra ĐIỂM TÀI CHÍNH
        if (matchesKeywords(normalized, SCORE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.FINANCIAL_SCORE)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 4: Kiểm tra XEM KẾ HOẠCH TÀI CHÍNH
        if (matchesKeywords(normalized, VIEW_PLAN_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.VIEW_FINANCIAL_PLAN)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 4.5: Kiểm tra MỤC TIÊU THU NHẬP (trước NGÂN SÁCH để tránh xung đột)
        if (matchesKeywords(normalized, CREATE_INCOME_GOAL_KW)) {
            // Vì các từ như "muc tieu" khá rộng, hãy kiểm tra ngữ cảnh
            return IntentResult.builder()
                    .intent(Intent.CREATE_INCOME_GOAL)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 5: Kiểm tra NGÂN SÁCH
        if (matchesKeywords(normalized, CREATE_BUDGET_KW)) {
            return IntentResult.builder()
                    .intent(Intent.CREATE_BUDGET)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 5: Kiểm tra TÓM TẮT HÀNG THÁNG / THÔNG TIN CHI SÂU
        if (matchesKeywords(normalized, INSIGHT_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.MONTHLY_SUMMARY)
                    .confidence(0.8)
                    .source(Source.RULE)
                    .build();
        }
        
        // Giai đoạn 5.5: Kiểm tra XÁC NHẬN (cho các bản nháp)
        if (matchesKeywords(normalized, CONFIRM_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 6: Kiểm tra TRUY VẤN
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

        // Giai đoạn 7: Kiểm tra LỜI KHUYÊN
        if (matchesKeywords(normalized, ADVICE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.FINANCIAL_ADVICE)
                    .confidence(0.8)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 8: THÊM nếu có số tiền
        if (hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.75)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 9: Độ tin cậy thấp — có thể là THÊM nhưng thiếu số tiền, hoặc trò chuyện chung
        return IntentResult.builder()
                .intent(Intent.UNKNOWN)
                .confidence(0.3)
                .source(Source.RULE)
                .build();
    }

    /**
     * Kiểm tra xem bất kỳ từ khóa nào có xuất hiện trong văn bản hay không.
     */
    private boolean matchesKeywords(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
