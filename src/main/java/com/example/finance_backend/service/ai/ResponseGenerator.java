package com.example.finance_backend.service.ai;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Generates natural language responses in Vietnamese and English.
 * Handles currency formatting, clarification templates, confirmation messages,
 * and financial summary formatting.
 */
@Component
public class ResponseGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Locale VI_LOCALE = new Locale("vi", "VN");
    private static final Locale EN_LOCALE = Locale.US;

    // ═════════════════════════════════════════════════════════
    // TRANSLATION HELPER
    // ═════════════════════════════════════════════════════════

    public String t(String language, String vi, String en) {
        return isEnglish(language) ? en : vi;
    }

    public boolean isEnglish(String language) {
        return "en".equals(language);
    }

    // ═════════════════════════════════════════════════════════
    // CURRENCY FORMATTING
    // ═════════════════════════════════════════════════════════

    public String formatVnd(BigDecimal value, String language) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
        Locale locale = isEnglish(language) ? EN_LOCALE : VI_LOCALE;
        String suffix = isEnglish(language) ? " VND" : " đ";
        return NumberFormat.getInstance(locale).format(normalized) + suffix;
    }

    // ═════════════════════════════════════════════════════════
    // INSERT RESPONSES
    // ═════════════════════════════════════════════════════════

    public String insertSuccess(int count, java.util.List<String> details, String language) {
        StringBuilder reply = new StringBuilder();
        reply.append(t(language, "Đã lưu ", "Saved "))
                .append(count)
                .append(t(language, " giao dịch.", " transaction(s)."));
        if (details != null && !details.isEmpty()) {
            reply.append("\n").append(String.join("\n", details));
        }
        return reply.toString();
    }

    public String insertEmpty(String language) {
        return t(language,
                "Mình chưa thấy khoản chi/thu nào rõ ràng. Bạn thử ghi: \"Hôm nay ăn phở 45k\" nhé.",
                "I couldn't detect a clear transaction. Try: \"I had pho for 45k today\".");
    }

    public String insertFailed(String error, String language) {
        return error != null && !error.isBlank()
                ? t(language, "Không thể lưu giao dịch: ", "Couldn't save transaction: ") + error
                : t(language,
                "Mình chưa thể tạo giao dịch. Hãy thử nhập rõ số tiền và nội dung nhé.",
                "I couldn't create the transaction. Please include a clear amount and description.");
    }

    // ═════════════════════════════════════════════════════════
    // QUERY RESPONSES
    // ═════════════════════════════════════════════════════════

    public String totalReply(LocalDate start, LocalDate end, BigDecimal total, String typeLabel, String language) {
        return isEnglish(language)
                ? String.format("%s from %s to %s is %s.", typeLabel, start.format(DATE_FMT), end.format(DATE_FMT), formatVnd(total, language))
                : String.format("%s từ %s đến %s là %s.", typeLabel, start.format(DATE_FMT), end.format(DATE_FMT), formatVnd(total, language));
    }

    public String averageReply(LocalDate start, LocalDate end, BigDecimal average, long days, String verb, String language) {
        return isEnglish(language)
                ? String.format("From %s to %s (%d days), your average daily %s is %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), days, verb, formatVnd(average, language))
                : String.format("Trong khoảng từ %s đến %s (%d ngày), trung bình mỗi ngày bạn %s %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), days, verb, formatVnd(average, language));
    }

    public String topCategoryReply(LocalDate start, LocalDate end, String catName, BigDecimal amount, String language) {
        return isEnglish(language)
                ? String.format("From %s to %s, you spent the most on %s: %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), catName, formatVnd(amount, language))
                : String.format("Trong khoảng %s đến %s, bạn chi nhiều nhất vào %s: %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), catName, formatVnd(amount, language));
    }

    public String percentageReply(LocalDate start, LocalDate end,
                                   java.util.List<Map.Entry<String, BigDecimal>> breakdown,
                                   BigDecimal total, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append(isEnglish(language)
                ? String.format("Spending breakdown from %s to %s:\n", start.format(DATE_FMT), end.format(DATE_FMT))
                : String.format("Tỷ lệ chi tiêu từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));

        for (var entry : breakdown) {
            BigDecimal pct = entry.getValue().multiply(new BigDecimal("100"))
                    .divide(total, 1, RoundingMode.HALF_UP);
            sb.append(String.format("- %s: %s%% (%s)\n", entry.getKey(), pct, formatVnd(entry.getValue(), language)));
        }
        return sb.toString().trim();
    }

    public String trendReply(LocalDate prevStart, LocalDate prevEnd, String label,
                              String trend, BigDecimal currentTotal, BigDecimal prevTotal, String language) {
        return isEnglish(language)
                ? String.format("Compared to the previous period (%s to %s), your total %s is %s. (Current: %s, Previous: %s)",
                prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend,
                formatVnd(currentTotal, language), formatVnd(prevTotal, language))
                : String.format("So với giai đoạn trước (%s đến %s), tổng %s của bạn %s. (Hiện tại: %s, Trước đó: %s)",
                prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend,
                formatVnd(currentTotal, language), formatVnd(prevTotal, language));
    }

    public String noTransactions(String language) {
        return t(language,
                "Không có giao dịch nào trong khoảng thời gian này.",
                "No transactions found in this period.");
    }

    public String noDataForPercentage(String language) {
        return t(language,
                "Không có dữ liệu để tính tỷ lệ.",
                "No data available to calculate percentages.");
    }

    public String noDataForTopCategory(String language) {
        return t(language,
                "Chưa có dữ liệu để tính nhóm chi tiêu lớn nhất.",
                "No data available to determine the top spending category.");
    }

    // ═════════════════════════════════════════════════════════
    // UPDATE / DELETE RESPONSES
    // ═════════════════════════════════════════════════════════

    public String updateSuccess(BigDecimal amount, String note, String language) {
        return t(language,
                "Đã cập nhật giao dịch: " + formatVnd(amount, language) + " - " + note,
                "Updated transaction: " + formatVnd(amount, language) + " - " + note);
    }

    public String updateNotFound(String language) {
        return t(language,
                "Mình không tìm thấy giao dịch nào khớp để sửa.",
                "I couldn't find a matching transaction to update.");
    }

    public String updateMultipleMatches(String language) {
        return t(language,
                "Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: ngày hoặc số tiền cụ thể).",
                "Multiple transactions match. Please be more specific (e.g., date or amount).");
    }

    public String updateWhatToChange(String language) {
        return t(language,
                "Bạn muốn sửa thông tin gì của giao dịch này?",
                "What would you like to update for this transaction?");
    }

    public String deleteSuccess(int count, String detail, String language) {
        if (count == 1 && detail != null) {
            return t(language,
                    "Đã xóa giao dịch: " + detail,
                    "Deleted transaction: " + detail);
        }
        return t(language,
                "Đã xóa " + count + " giao dịch khớp với yêu cầu.",
                "Deleted " + count + " matching transactions.");
    }

    public String deleteNotFound(String language) {
        return t(language,
                "Mình không tìm thấy giao dịch nào khớp để xóa.",
                "I couldn't find a matching transaction to delete.");
    }

    public String deleteMultipleMatches(String language) {
        return t(language,
                "Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: 'Xóa tất cả' hoặc số tiền cụ thể).",
                "Multiple transactions match. Please be more specific (e.g., 'delete all' or a specific amount).");
    }

    // ═════════════════════════════════════════════════════════
    // GENERIC / ERROR RESPONSES
    // ═════════════════════════════════════════════════════════

    public String unknownMessage(String language) {
        return t(language,
                "Mình chưa hiểu rõ ý bạn. Hãy thử: \"Tháng này tôi tiêu nhiều nhất vào cái gì?\" hoặc \"Hôm nay tôi ăn phở 45k\".",
                "I didn't quite understand. Try: \"What did I spend the most on this month?\" or \"I had pho for 45k today\".");
    }

    public String emptyInputMessage(String language) {
        return t(language,
                "Bạn hãy nhập câu hỏi hoặc nội dung chi tiêu để mình xử lý nhé.",
                "Please enter a question or a transaction so I can help.");
    }

    public String aiConnectionError(String language) {
        return t(language,
                "Mình chưa hiểu rõ ý bạn. Bạn có thể nói rõ hơn một chút không?",
                "I'm not sure I understand. Could you please be more specific?");
    }

    public String noCategories(String language) {
        return t(language,
                "Chưa có danh mục chi tiêu nào. Hãy tạo danh mục trước nhé.",
                "No categories found. Please create a category first.");
    }

    public String noAccount(String language) {
        return t(language,
                "Bạn chưa có tài khoản/ví. Hãy tạo tài khoản trước khi thêm giao dịch nhé.",
                "You don't have any wallet/account yet. Please create one before adding a transaction.");
    }

    public String needAccountSelection(String language) {
        return t(language,
                "Bạn muốn dùng ví nào cho giao dịch này?",
                "Which wallet should I use for this transaction?");
    }

    /** Trim note for display (max 80 chars). */
    public String trimNote(String note) {
        if (note == null) return "";
        String trimmed = note.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }
}
