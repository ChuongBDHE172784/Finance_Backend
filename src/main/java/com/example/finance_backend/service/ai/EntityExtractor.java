package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.ParsedMessage;
import com.example.finance_backend.dto.TransactionSlot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Extracts transaction entities (amount, category, note, date, type)
 * from preprocessed text using rule-based keyword matching.
 */
@Component
public class EntityExtractor {

    private final TextPreprocessor textPreprocessor;

    public EntityExtractor(TextPreprocessor textPreprocessor) {
        this.textPreprocessor = textPreprocessor;
    }

    // ═════════════════════════════════════════════════════════
    // TRANSACTION SLOT EXTRACTION
    // ═════════════════════════════════════════════════════════

    /**
     * Extracts transaction slots from a parsed message.
     * Handles multi-transaction messages (e.g., "phở 45k, cà phê 30k").
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

            slots.add(TransactionSlot.builder()
                    .amount(amount)
                    .categoryName(category)
                    .note(sub.trim())
                    .type(type)
                    .date(date)
                    .confidence(amount != null ? 0.85 : 0.4)
                    .build());
        }
        return slots;
    }

    // ═════════════════════════════════════════════════════════
    // CATEGORY INFERENCE
    // ═════════════════════════════════════════════════════════

    /**
     * Infers category from keywords in the normalized text.
     * Returns the Vietnamese category name or null if uncertain.
     */
    public String inferCategory(String normalizedText) {
        if (normalizedText == null) return null;
        for (var entry : CATEGORY_KEYWORDS.entrySet()) {
            if (normalizedText.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════
    // TRANSACTION TYPE DETECTION
    // ═════════════════════════════════════════════════════════

    /**
     * Detects whether the transaction is EXPENSE or INCOME.
     */
    public String inferTransactionType(String normalizedText) {
        if (normalizedText == null) return "EXPENSE";
        if (TextPreprocessor.containsAny(normalizedText, INCOME_KEYWORDS)) return "INCOME";
        return "EXPENSE";
    }

    // ═════════════════════════════════════════════════════════
    // QUERY PARAMETER DETECTION
    // ═════════════════════════════════════════════════════════

    /**
     * Detects the query metric from text.
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
     * Detects the query type filter (EXPENSE/INCOME/ALL).
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
    // CATEGORY NORMALIZATION
    // ═════════════════════════════════════════════════════════

    /**
     * Normalizes English/Vietnamese category names to the canonical Vietnamese names
     * used in the database.
     */
    public String normalizeCategoryName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        String normalized = textPreprocessor.normalizeVietnamese(trimmed);

        // Income aliases
        if (Set.of("nap vi", "nap tien", "top up", "topup", "deposit", "income",
                "salary", "wage", "bonus", "refund", "cashback", "transfer in").contains(normalized)) {
            return "Nạp tiền";
        }
        // Food aliases
        if (Set.of("food", "meal", "lunch", "dinner", "breakfast", "restaurant",
                "coffee", "tea", "drink", "milk tea", "snack").contains(normalized)) {
            return "Ăn uống";
        }
        if ("parking".equals(normalized)) return "Gửi xe";
        // Transport aliases
        if (Set.of("gas", "gasoline", "petrol", "fuel", "transport", "transportation",
                "taxi", "uber", "grab").contains(normalized)) {
            return "Xăng xe";
        }
        // Shopping aliases
        if (Set.of("shopping", "groceries", "supermarket", "mall", "clothes",
                "purchase", "buy").contains(normalized)) {
            return "Mua sắm";
        }
        // Entertainment aliases
        if (Set.of("entertainment", "movie", "cinema", "netflix", "game",
                "gift", "present", "birthday").contains(normalized)) {
            return "Giải trí";
        }
        // Health aliases
        if (Set.of("health", "medical", "hospital", "medicine", "pharmacy").contains(normalized)) {
            return "Y tế";
        }
        // Education aliases
        if (Set.of("education", "school", "tuition", "book", "course", "study").contains(normalized)) {
            return "Giáo dục";
        }
        if (Set.of("other", "others").contains(normalized)) return "Khác";

        return trimmed;
    }

    // ═════════════════════════════════════════════════════════
    // KEYWORD MAPS
    // ═════════════════════════════════════════════════════════

    private static final List<String> INCOME_KEYWORDS = List.of(
            "thu", "thu nhap", "luong", "thuong", "nhan tien",
            "nap", "nap tien", "nap vao", "vao vi", "chuyen vao", "tien ve",
            "hoan tien", "refund", "nap vi",
            "income", "salary", "wage", "bonus", "receive", "received",
            "deposit", "top up", "topup", "transfer in", "cashback");

    /** Category keyword map: normalized keyword → Vietnamese category name */
    private static final LinkedHashMap<String, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        // Food & Drink
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

        // Transport
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

        // Parking
        CATEGORY_KEYWORDS.put("gui xe", "Gửi xe");
        CATEGORY_KEYWORDS.put("ve xe", "Gửi xe");
        CATEGORY_KEYWORDS.put("parking", "Gửi xe");

        // Shopping
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

        // Entertainment
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

        // Health
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

        // Education
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

        // Housing
        CATEGORY_KEYWORDS.put("thue nha", "Nhà cửa");
        CATEGORY_KEYWORDS.put("thue", "Nhà cửa");
        CATEGORY_KEYWORDS.put("dien", "Nhà cửa");
        CATEGORY_KEYWORDS.put("internet", "Nhà cửa");
        CATEGORY_KEYWORDS.put("wifi", "Nhà cửa");
        CATEGORY_KEYWORDS.put("giat", "Nhà cửa");
        CATEGORY_KEYWORDS.put("rent", "Nhà cửa");

        // Income
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
