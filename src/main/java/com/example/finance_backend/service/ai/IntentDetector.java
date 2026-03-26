package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.IntentResult;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.dto.IntentResult.Source;
import com.example.finance_backend.dto.ParsedMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Trình phát hiện ý định (intent) hai giai đoạn: dựa trên quy tắc trước (nhanh, không tốn chi phí API),
 * dự phòng Gemini khi các quy tắc không thể xác định được ý định.
 */
@Component
public class IntentDetector {

    // ── từ khóa XÓA ──
    private static final List<String> DELETE_KW = List.of(
            "xoa", "huy", "bo qua", "delete", "remove", "erase", "clear", "cancel");

    private static final List<String> INSERT_KW = List.of(
            "mua", "an", "uong", "thue", "do", "nap", "dong", "tra", "rut", "chuyen",
            "hoa don", "bien lai", "phieu thu", "bill", "invoice", "receipt", "voucher");

    private static final List<String> UPDATE_BASE_KW = List.of(
            "doi", "cap nhat", "update", "change", "edit", "set", "replace");

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

    // ── từ khóa LỊCH TRÌNH ──
    private static final List<String> CREATE_SCHEDULE_KW = List.of(
            "tao lich", "dat lich", "nhac toi", "nhac lich", "len lich", "tu dong", "mo lich",
            "create schedule", "set schedule", "remind me");

    private static final List<String> REPEAT_KW = List.of(
            "hang ngay", "moi ngay", "hang tuan", "moi tuan", "hang thang", "moi thang", "dinh ky", "hang nam", "moi nam",
            "every day", "daily", "every week", "weekly", "every month", "monthly", "every year", "yearly");

    private static final List<String> DISABLE_SCHEDULE_KW = List.of(
            "tam dung lich", "tat lich", "dung lich", "pause schedule", "disable schedule");

    private static final List<String> ENABLE_SCHEDULE_KW = List.of(
            "bat lai lich", "mo lai lich", "resume schedule", "enable schedule");

    private static final List<String> DELETE_SCHEDULE_KW = List.of(
            "xoa lich", "huy lich", "delete schedule", "cancel schedule");

    private static final List<String> LIST_SCHEDULES_KW = List.of(
            "nhung lich", "danh sach lich", "cac lich", "schedules", "list schedule");

    private static final List<String> UPCOMING_KW = List.of(
            "sap toi", "khoan nao", "khoan phai tra", "sap den han", "upcoming");

    // ── từ khóa GIẢI THÍCH GIAO DỊCH ──
    private static final List<String> EXPLAIN_KW = List.of(
            "tu dau", "vi sao", "tai sao", "giai thich", "khoan nay la gi", "tu lich nao", "explain");

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
        String original = parsed.getOriginalText().toLowerCase();
        
        if (normalized == null || normalized.isBlank()) {
            return IntentResult.builder()
                    .intent(Intent.UNKNOWN)
                    .confidence(0.0)
                    .source(Source.RULE)
                    .build();
        }

        boolean hasAmount = parsed.hasAmounts();

        // Giai đoạn 0.1: Kiểm tra GIẢI THÍCH GIAO DỊCH
        if (containsWord(normalized, EXPLAIN_KW) && (normalized.contains("khoan") || normalized.contains("tien") || normalized.contains("nay") || normalized.contains("giao dich") || normalized.contains("chi") || normalized.contains("thu"))) {
            return IntentResult.builder()
                    .intent(Intent.EXPLAIN_TRANSACTION_SOURCE)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 0.2: Kiểm tra LỊCH TRÌNH / SCHEDULES
        if (containsWord(normalized, DISABLE_SCHEDULE_KW)) {
            return IntentResult.builder().intent(Intent.DISABLE_SCHEDULE).confidence(0.95).source(Source.RULE).build();
        }
        if (containsWord(normalized, ENABLE_SCHEDULE_KW)) {
            return IntentResult.builder().intent(Intent.ENABLE_SCHEDULE).confidence(0.95).source(Source.RULE).build();
        }
        if (containsWord(normalized, DELETE_SCHEDULE_KW)) {
            return IntentResult.builder().intent(Intent.DELETE_SCHEDULE).confidence(0.95).source(Source.RULE).build();
        }
        if (containsWord(normalized, LIST_SCHEDULES_KW)) {
            return IntentResult.builder().intent(Intent.LIST_SCHEDULES).confidence(0.95).source(Source.RULE).build();
        }
        if (containsWord(normalized, UPCOMING_KW) && (normalized.contains("tra") || normalized.contains("chi") || normalized.contains("tieu") || normalized.contains("khoan"))) {
            return IntentResult.builder().intent(Intent.LIST_UPCOMING_TRANSACTIONS).confidence(0.95).source(Source.RULE).build();
        }

        // Ưu tiên CREATE_SCHEDULE khi có các từ khóa lặp lại hoặc tạo lịch rõ ràng
        boolean isRepeat = containsWord(normalized, REPEAT_KW);
        boolean isExplicitSchedule = containsWord(normalized, CREATE_SCHEDULE_KW);

        if (isExplicitSchedule || (isRepeat && hasAmount)) {
            return IntentResult.builder()
                    .intent(Intent.CREATE_SCHEDULE)
                    .confidence(0.9) 
                    .source(Source.RULE)
                    .build();
        }

        // Ưu tiên cao cho INSERT nếu có các lý do rõ ràng hoặc có số tiền với động từ
        boolean hasInsertKW = containsWord(normalized, INSERT_KW);
        if (hasAmount && hasInsertKW) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Trường hợp gửi ảnh hóa đơn nhưng không có số tiền trong text
        if (hasInsertKW && (normalized.contains("hoa don") || normalized.contains("bill") || normalized.contains("invoice") || normalized.contains("receipt"))) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.7) // Thấp hơn chút vì thiếu số tiền, để Gemini xử lý chính
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 1: Kiểm tra XÓA (ưu tiên cao nhất khi có từ khóa)
        if (containsWord(normalized, DELETE_KW)
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
        // Kiểm tra kỹ hơn từ "sua" (vì hay nhầm với trà sữa, sữa tươi)
        boolean hasUpdateKW = containsWord(normalized, UPDATE_BASE_KW);
        boolean hasSua = containsWord(normalized, List.of("sua"));
        boolean hasThanh = containsWord(normalized, List.of("thanh"));

        if (hasUpdateKW || (hasSua && original.contains("sửa")) || (hasThanh && !normalized.contains("thanh toan"))) {
             return IntentResult.builder()
                    .intent(Intent.UPDATE_TRANSACTION)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 3: Kiểm tra ĐIỂM TÀI CHÍNH
        if (containsWord(normalized, SCORE_KW)) {
            return IntentResult.builder()
                    .intent(Intent.FINANCIAL_SCORE)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 4: Kiểm tra XEM KẾ HOẠCH TÀI CHÍNH
        if (containsWord(normalized, VIEW_PLAN_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.VIEW_FINANCIAL_PLAN)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 4.5: Kiểm tra MỤC TIÊU THU NHẬP
        if (containsWord(normalized, CREATE_INCOME_GOAL_KW)) {
            return IntentResult.builder()
                    .intent(Intent.CREATE_INCOME_GOAL)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 5: Kiểm tra NGÂN SÁCH
        if (containsWord(normalized, CREATE_BUDGET_KW)) {
            return IntentResult.builder()
                    .intent(Intent.CREATE_BUDGET)
                    .confidence(0.85)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 5: Kiểm tra TÓM TẮT HÀNG THÁNG
        if (containsWord(normalized, INSIGHT_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.MONTHLY_SUMMARY)
                    .confidence(0.8)
                    .source(Source.RULE)
                    .build();
        }
        
        // Giai đoạn 5.5: Kiểm tra XÁC NHẬN
        if (containsWord(normalized, CONFIRM_KW) && !hasAmount) {
            return IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.9)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 6: Kiểm tra TRUY VẤN
        boolean isQuery = containsWord(normalized, QUERY_KW);
        boolean isTimeQuery = containsWord(normalized, TIME_QUERY_KW);
        if (isQuery || (isTimeQuery && !hasAmount)) {
            double confidence = isQuery ? 0.85 : 0.65;
            return IntentResult.builder()
                    .intent(Intent.QUERY_TRANSACTION)
                    .confidence(confidence)
                    .source(Source.RULE)
                    .build();
        }

        // Giai đoạn 7: Kiểm tra LỜI KHUYÊN
        if (containsWord(normalized, ADVICE_KW)) {
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

        // Giai đoạn 9: Độ tin cậy thấp
        return IntentResult.builder()
                .intent(Intent.UNKNOWN)
                .confidence(0.3)
                .source(Source.RULE)
                .build();
    }

    /**
     * Kiểm tra xem bất kỳ từ khóa nào có xuất hiện như một từ độc lập trong văn bản hay không.
     */
    private boolean containsWord(String text, List<String> keywords) {
        if (text == null || keywords == null) return false;
        for (String kw : keywords) {
            String regex = "\\b" + Pattern.quote(kw) + "\\b";
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
