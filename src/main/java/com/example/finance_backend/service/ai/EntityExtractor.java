package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.ParsedMessage;
import com.example.finance_backend.dto.TransactionSlot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Trích xuất các thực thể giao dịch (số tiền, danh mục, ghi chú, ngày, loại)
 * từ văn bản đã qua tiền xử lý bằng cách sử dụng khớp từ khóa dựa trên quy tắc.
 */
@Component
public class EntityExtractor {

    private final TextPreprocessor textPreprocessor;

    public EntityExtractor(TextPreprocessor textPreprocessor) {
        this.textPreprocessor = textPreprocessor;
    }

    // ═════════════════════════════════════════════════════════
    // TRÍCH XUẤT CÁC Ô GIAO DỊCH
    // ═════════════════════════════════════════════════════════

    /**
     * Trích xuất các ô (slots) giao dịch từ một tin nhắn đã được phân tích.
     * Xử lý các tin nhắn có nhiều giao dịch (ví dụ: "phở 45k, cà phê 30k").
     */
    public List<TransactionSlot> extractTransactionSlots(ParsedMessage parsed) {
        List<String> subMessages = parsed.getSubMessages();
        if (subMessages == null || subMessages.isEmpty()) {
            subMessages = List.of(parsed.getOriginalText());
        }

        List<TransactionSlot> slots = new ArrayList<>();
        for (String sub : subMessages) {
            BigDecimal amount = textPreprocessor.extractSingleAmount(sub);
            String normalized = textPreprocessor.normalizeVietnamese(sub);
            String category = inferCategory(normalized);
            String type = inferTransactionType(normalized);
            String date = parsed.getStartDate() != null
                    ? parsed.getStartDate().toString()
                    : LocalDate.now().toString();
            String repeatType = inferRepeatType(normalized);
            String repeatConfig = inferRepeatConfig(normalized, repeatType);

            slots.add(TransactionSlot.builder()
                    .amount(amount)
                    .categoryName(category)
                    .note(sub.trim())
                    .type(type)
                    .date(date)
                    .repeatType(repeatType)
                    .repeatConfig(repeatConfig)
                    .confidence(amount != null ? 0.85 : 0.4)
                    .build());
        }
        return slots;
    }

    // ═════════════════════════════════════════════════════════
    // PHÁT HIỆN LỊCH TRÌNH VÀ LẶP LẠI
    // ═════════════════════════════════════════════════════════

    /**
     * Suy luận loại lặp lại từ văn bản (ví dụ: MONTHLY, DAILY).
     */
    public String inferRepeatType(String normalizedText) {
        if (normalizedText == null) return "NONE";
        if (TextPreprocessor.containsAny(normalizedText, "hang ngay", "moi ngay", "every day", "daily")) return "DAILY";
        if (TextPreprocessor.containsAny(normalizedText, "hang tuan", "moi tuan", "every week", "weekly")) return "WEEKLY";
        if (TextPreprocessor.containsAny(normalizedText, "hang thang", "moi thang", "every month", "monthly")) return "MONTHLY";
        if (TextPreprocessor.containsAny(normalizedText, "hang nam", "moi nam", "every year", "yearly")) return "YEARLY";
        if (TextPreprocessor.containsAny(normalizedText, "dinh ky")) return "CUSTOM";
        return "NONE";
    }

    /**
     * Suy luận cấu hình lặp lại (JSON format) như ngày trong tháng.
     */
    public String inferRepeatConfig(String normalizedText, String repeatType) {
        if (normalizedText == null) return null;
        if ("MONTHLY".equals(repeatType)) {
            // Match "ngay 15" or just "15"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(ngay|ngay\\s+thu)?\\s*(\\d{1,2})").matcher(normalizedText);
            if (m.find()) {
                try {
                    int day = Integer.parseInt(m.group(2));
                    if (day >= 1 && day <= 31) {
                        return String.valueOf(day);
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        } else if ("WEEKLY".equals(repeatType)) {
            if (normalizedText.matches(".*\\b(thu\\s+2|thu\\s+hai|t2|monday|mon)\\b.*")) return "1";
            if (normalizedText.matches(".*\\b(thu\\s+3|thu\\s+ba|t3|tuesday|tue)\\b.*")) return "2";
            if (normalizedText.matches(".*\\b(thu\\s+4|thu\\s+tu|t4|wednesday|wed)\\b.*")) return "3";
            if (normalizedText.matches(".*\\b(thu\\s+5|thu\\s+nam|t5|thursday|thu)\\b.*")) return "4";
            if (normalizedText.matches(".*\\b(thu\\s+6|thu\\s+sau|t6|friday|fri)\\b.*")) return "5";
            if (normalizedText.matches(".*\\b(thu\\s+7|thu\\s+bay|t7|saturday|sat)\\b.*")) return "6";
            if (normalizedText.matches(".*\\b(chu\\s+nhat|cn|sunday|sun)\\b.*")) return "7";
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════
    // SUY LUẬN DANH MỤC
    // ═════════════════════════════════════════════════════════

    /**
     * Suy luận danh mục từ các từ khóa trong văn bản đã được chuẩn hóa.
     * Trả về tên danh mục tiếng Việt hoặc null nếu không chắc chắn.
     */
    public String inferCategory(String normalizedText) {
        if (normalizedText == null) return null;
        for (var entry : CATEGORY_KEYWORDS.entrySet()) {
            String kw = entry.getKey();
            // Sử dụng ranh giới từ để tránh các kết quả khớp sai như "tháng" khớp với "an"
            if (normalizedText.matches(".*\\b" + java.util.regex.Pattern.quote(kw) + "\\b.*")) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════
    // PHÁT HIỆN LOẠI GIAO DỊCH
    // ═════════════════════════════════════════════════════════

    /**
     * Phát hiện xem giao dịch là CHI TIÊU hay THU NHẬP.
     */
    public String inferTransactionType(String normalizedText) {
        if (normalizedText == null) return "EXPENSE";
        if (TextPreprocessor.containsAny(normalizedText, INCOME_KEYWORDS)) return "INCOME";
        return "EXPENSE";
    }

    // ═════════════════════════════════════════════════════════
    // PHÁT HIỆN CÁC THAM SỐ TRUY VẤN
    // ═════════════════════════════════════════════════════════

    /**
     * Phát hiện chỉ số truy vấn từ văn bản.
     */
    public String detectMetric(String normalizedText) {
        if (normalizedText == null) return "TOTAL";
        if (TextPreprocessor.containsAny(normalizedText, "diem tai chinh", "financial score", "cham diem", "score"))
            return "FINANCIAL_SCORE";
        if (TextPreprocessor.containsAny(normalizedText, "ngan sach", "budget", "han muc"))
            return "BUDGET";
        if (TextPreprocessor.containsAny(normalizedText, "tom tat", "bao cao thang", "monthly summary", "monthly report", "tong ket"))
            return "MONTHLY_SUMMARY";
        if (TextPreprocessor.containsAny(normalizedText, "suc khoe tai chinh", "financial health", "savings rate", "ty le tiet kiem"))
            return "FINANCIAL_HEALTH";
        if (TextPreprocessor.containsAny(normalizedText, "cuoi tuan", "weekend", "thu 7", "chu nhat", "weekday"))
            return "WEEKLY_PATTERN";
        if (TextPreprocessor.containsAny(normalizedText, "goi y", "de xuat", "suggest", "recommendation"))
            return "SMART_SUGGESTION";
        if (TextPreprocessor.containsAny(normalizedText, "nhieu nhat", "cao nhat", "most", "highest", "top"))
            return "TOP_CATEGORY";
        if (TextPreprocessor.containsAny(normalizedText, "danh sach", "liet ke", "list", "show", "detail", "details"))
            return "LIST";
        if (TextPreprocessor.containsAny(normalizedText, "trung binh", "average", "avg", "per day"))
            return "AVERAGE";
        if (TextPreprocessor.containsAny(normalizedText, "xu huong", "tang", "giam", "trend", "increase", "decrease", "compared"))
            return "TREND";
        if (TextPreprocessor.containsAny(normalizedText, "ty le", "phan tram", "percentage", "percent", "ratio"))
            return "PERCENTAGE";
        return "TOTAL";
    }

    /**
     * Phát hiện bộ lọc loại truy vấn (CHI TIÊU/THU NHẬP/TẤT CẢ).
     */
    public String detectQueryType(String normalizedText) {
        if (normalizedText == null) return "EXPENSE";
        if (TextPreprocessor.containsAny(normalizedText, INCOME_KEYWORDS)) return "INCOME";
        if (TextPreprocessor.containsAny(normalizedText, List.of("chi", "tieu", "mua",
                "expense", "spend", "spent", "buy", "purchase", "pay", "payment")))
            return "EXPENSE";
        return "EXPENSE";
    }

    // ═════════════════════════════════════════════════════════
    // CHUẨN HÓA DANH MỤC
    // ═════════════════════════════════════════════════════════

    /**
     * Chuẩn hóa tên danh mục tiếng Anh/tiếng Việt sang tên tiếng Việt chuẩn
     * được sử dụng trong cơ sở dữ liệu.
     */
    public String normalizeCategoryName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        String normalized = textPreprocessor.normalizeVietnamese(trimmed);

        // Các bí danh cho Thu nhập
        if (Set.of("nap vi", "nap tien", "top up", "topup", "deposit", "income",
                "salary", "wage", "bonus", "refund", "cashback", "transfer in").contains(normalized)) {
            return "Nạp tiền";
        }
        // Các bí danh cho Ăn uống
        if (Set.of("food", "meal", "lunch", "dinner", "breakfast", "restaurant",
                "coffee", "tea", "drink", "milk tea", "snack").contains(normalized)) {
            return "Ăn uống";
        }
        if ("parking".equals(normalized)) return "Gửi xe";
        // Các bí danh cho Xăng xe
        if (Set.of("gas", "gasoline", "petrol", "fuel", "transport", "transportation",
                "taxi", "uber", "grab").contains(normalized)) {
            return "Xăng xe";
        }
        // Các bí danh cho Mua sắm
        if (Set.of("shopping", "groceries", "supermarket", "mall", "clothes",
                "purchase", "buy").contains(normalized)) {
            return "Mua sắm";
        }
        // Các bí danh cho Giải trí
        if (Set.of("entertainment", "movie", "cinema", "netflix", "game",
                "gift", "present", "birthday").contains(normalized)) {
            return "Giải trí";
        }
        // Các bí danh cho Y tế
        if (Set.of("health", "medical", "hospital", "medicine", "pharmacy").contains(normalized)) {
            return "Y tế";
        }
        // Các bí danh cho Giáo dục
        if (Set.of("education", "school", "tuition", "book", "course", "study").contains(normalized)) {
            return "Giáo dục";
        }
        if (Set.of("other", "others").contains(normalized)) return "Khác";

        return trimmed;
    }

    // ═════════════════════════════════════════════════════════
    // CÁC BẢN ĐỒ TỪ KHÓA
    // ═════════════════════════════════════════════════════════

    private static final List<String> INCOME_KEYWORDS = List.of(
            "thu", "thu nhap", "luong", "thuong", "nhan tien",
            "nap", "nap tien", "nap vao", "vao vi", "chuyen vao", "tien ve",
            "hoan tien", "refund", "nap vi",
            "income", "salary", "wage", "bonus", "receive", "received",
            "deposit", "top up", "topup", "transfer in", "cashback");

    /** Bản đồ từ khóa danh mục: từ khóa đã chuẩn hóa → tên danh mục tiếng Việt */
    private static final LinkedHashMap<String, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        // Ăn uống
        CATEGORY_KEYWORDS.put("ca phe", "Ăn uống");
        CATEGORY_KEYWORDS.put("cafe", "Ăn uống");
        CATEGORY_KEYWORDS.put("coffee", "Ăn uống");
        CATEGORY_KEYWORDS.put("milk tea", "Ăn uống");
        CATEGORY_KEYWORDS.put("breakfast", "Ăn uống");
        CATEGORY_KEYWORDS.put("lunch", "Ăn uống");
        CATEGORY_KEYWORDS.put("dinner", "Ăn uống");
        CATEGORY_KEYWORDS.put("restaurant", "Ăn uống");
        CATEGORY_KEYWORDS.put("food", "Ăn uống");
        CATEGORY_KEYWORDS.put("meal", "Ăn uống");
        CATEGORY_KEYWORDS.put("snack", "Ăn uống");
        CATEGORY_KEYWORDS.put("pho", "Ăn uống");
        CATEGORY_KEYWORDS.put("com", "Ăn uống");
        CATEGORY_KEYWORDS.put("bun", "Ăn uống");
        CATEGORY_KEYWORDS.put("banh", "Ăn uống");
        CATEGORY_KEYWORDS.put("nuoc", "Ăn uống");
        CATEGORY_KEYWORDS.put("uong", "Ăn uống");
        CATEGORY_KEYWORDS.put("nhau", "Ăn uống");
        CATEGORY_KEYWORDS.put("an ", "Ăn uống");

        // Di chuyển/Xăng xe
        CATEGORY_KEYWORDS.put("do xang", "Xăng xe");
        CATEGORY_KEYWORDS.put("xe may", "Xăng xe");
        CATEGORY_KEYWORDS.put("o to", "Xăng xe");
        CATEGORY_KEYWORDS.put("oto", "Xăng xe");
        CATEGORY_KEYWORDS.put("gasoline", "Xăng xe");
        CATEGORY_KEYWORDS.put("petrol", "Xăng xe");
        CATEGORY_KEYWORDS.put("fuel", "Xăng xe");
        CATEGORY_KEYWORDS.put("transportation", "Xăng xe");
        CATEGORY_KEYWORDS.put("transport", "Xăng xe");
        CATEGORY_KEYWORDS.put("taxi", "Xăng xe");
        CATEGORY_KEYWORDS.put("uber", "Xăng xe");
        CATEGORY_KEYWORDS.put("grab", "Xăng xe");
        CATEGORY_KEYWORDS.put("toll", "Xăng xe");
        CATEGORY_KEYWORDS.put("xang", "Xăng xe");
        CATEGORY_KEYWORDS.put("gas", "Xăng xe");
        CATEGORY_KEYWORDS.put("xe", "Xăng xe");

        // Gửi xe
        CATEGORY_KEYWORDS.put("gui xe", "Gửi xe");
        CATEGORY_KEYWORDS.put("ve xe", "Gửi xe");
        CATEGORY_KEYWORDS.put("parking", "Gửi xe");

        // Mua sắm
        CATEGORY_KEYWORDS.put("sieu thi", "Mua sắm");
        CATEGORY_KEYWORDS.put("quan ao", "Mua sắm");
        CATEGORY_KEYWORDS.put("my pham", "Mua sắm");
        CATEGORY_KEYWORDS.put("shopee", "Mua sắm");
        CATEGORY_KEYWORDS.put("lazada", "Mua sắm");
        CATEGORY_KEYWORDS.put("tiki", "Mua sắm");
        CATEGORY_KEYWORDS.put("supermarket", "Mua sắm");
        CATEGORY_KEYWORDS.put("groceries", "Mua sắm");
        CATEGORY_KEYWORDS.put("mall", "Mua sắm");
        CATEGORY_KEYWORDS.put("clothes", "Mua sắm");
        CATEGORY_KEYWORDS.put("shopping", "Mua sắm");
        CATEGORY_KEYWORDS.put("purchase", "Mua sắm");
        CATEGORY_KEYWORDS.put("buy", "Mua sắm");
        CATEGORY_KEYWORDS.put("giay", "Mua sắm");
        CATEGORY_KEYWORDS.put("tui", "Mua sắm");
        CATEGORY_KEYWORDS.put("sam", "Mua sắm");
        CATEGORY_KEYWORDS.put("mua", "Mua sắm");

        // Giải trí
        CATEGORY_KEYWORDS.put("du lich", "Giải trí");
        CATEGORY_KEYWORDS.put("sinh nhat", "Giải trí");
        CATEGORY_KEYWORDS.put("qua tang", "Giải trí");
        CATEGORY_KEYWORDS.put("giai tri", "Giải trí");
        CATEGORY_KEYWORDS.put("entertainment", "Giải trí");
        CATEGORY_KEYWORDS.put("movie", "Giải trí");
        CATEGORY_KEYWORDS.put("cinema", "Giải trí");
        CATEGORY_KEYWORDS.put("netflix", "Giải trí");
        CATEGORY_KEYWORDS.put("game", "Giải trí");
        CATEGORY_KEYWORDS.put("gift", "Giải trí");
        CATEGORY_KEYWORDS.put("present", "Giải trí");
        CATEGORY_KEYWORDS.put("phim", "Giải trí");
        CATEGORY_KEYWORDS.put("rap", "Giải trí");
        CATEGORY_KEYWORDS.put("choi", "Giải trí");

        // Y tế
        CATEGORY_KEYWORDS.put("benh vien", "Y tế");
        CATEGORY_KEYWORDS.put("bac si", "Y tế");
        CATEGORY_KEYWORDS.put("y te", "Y tế");
        CATEGORY_KEYWORDS.put("thuoc", "Y tế");
        CATEGORY_KEYWORDS.put("siro", "Y tế");
        CATEGORY_KEYWORDS.put("kham", "Y tế");
        CATEGORY_KEYWORDS.put("health", "Y tế");
        CATEGORY_KEYWORDS.put("medical", "Y tế");
        CATEGORY_KEYWORDS.put("hospital", "Y tế");
        CATEGORY_KEYWORDS.put("medicine", "Y tế");
        CATEGORY_KEYWORDS.put("pharmacy", "Y tế");

        // Giáo dục
        CATEGORY_KEYWORDS.put("hoc phi", "Giáo dục");
        CATEGORY_KEYWORDS.put("khoa hoc", "Giáo dục");
        CATEGORY_KEYWORDS.put("education", "Giáo dục");
        CATEGORY_KEYWORDS.put("school", "Giáo dục");
        CATEGORY_KEYWORDS.put("tuition", "Giáo dục");
        CATEGORY_KEYWORDS.put("book", "Giáo dục");
        CATEGORY_KEYWORDS.put("course", "Giáo dục");
        CATEGORY_KEYWORDS.put("study", "Giáo dục");
        CATEGORY_KEYWORDS.put("hoc", "Giáo dục");
        CATEGORY_KEYWORDS.put("sach", "Giáo dục");

        // Nhà cửa
        CATEGORY_KEYWORDS.put("thue nha", "Nhà cửa");
        CATEGORY_KEYWORDS.put("thue", "Nhà cửa");
        CATEGORY_KEYWORDS.put("dien", "Nhà cửa");
        CATEGORY_KEYWORDS.put("internet", "Nhà cửa");
        CATEGORY_KEYWORDS.put("wifi", "Nhà cửa");
        CATEGORY_KEYWORDS.put("giat", "Nhà cửa");
        CATEGORY_KEYWORDS.put("rent", "Nhà cửa");

        // Thu nhập
        CATEGORY_KEYWORDS.put("nap vao", "Nạp tiền");
        CATEGORY_KEYWORDS.put("vao vi", "Nạp tiền");
        CATEGORY_KEYWORDS.put("chuyen vao", "Nạp tiền");
        CATEGORY_KEYWORDS.put("hoan tien", "Nạp tiền");
        CATEGORY_KEYWORDS.put("nap vi", "Nạp tiền");
        CATEGORY_KEYWORDS.put("nap tien", "Nạp tiền");
        CATEGORY_KEYWORDS.put("thu nhap", "Nạp tiền");
        CATEGORY_KEYWORDS.put("tien ve", "Nạp tiền");
        CATEGORY_KEYWORDS.put("luong", "Nạp tiền");
        CATEGORY_KEYWORDS.put("thuong", "Nạp tiền");
        CATEGORY_KEYWORDS.put("income", "Nạp tiền");
        CATEGORY_KEYWORDS.put("salary", "Nạp tiền");
        CATEGORY_KEYWORDS.put("wage", "Nạp tiền");
        CATEGORY_KEYWORDS.put("bonus", "Nạp tiền");
        CATEGORY_KEYWORDS.put("deposit", "Nạp tiền");
        CATEGORY_KEYWORDS.put("top up", "Nạp tiền");
        CATEGORY_KEYWORDS.put("topup", "Nạp tiền");
        CATEGORY_KEYWORDS.put("cashback", "Nạp tiền");
        CATEGORY_KEYWORDS.put("refund", "Nạp tiền");
        CATEGORY_KEYWORDS.put("transfer in", "Nạp tiền");
    }
}
