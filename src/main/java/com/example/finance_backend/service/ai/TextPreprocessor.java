package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.ParsedMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thành phần trong đường ống tiền xử lý văn bản.
 * Xử lý: chuẩn hóa văn bản, trích xuất số tiền, phát hiện ngày tháng,
 * tách các giao dịch đa tầng, và phát hiện ngôn ngữ.
 */
@Component
public class TextPreprocessor {

    // ── Các mẫu định dạng tiền (thứ tự quan trọng: cụ thể nhất trước) ──
    private static final Pattern PAT_TR_MIXED =
            Pattern.compile("(?:^|\\s)(\\d+)\\s*(?:tr(?:ieu|iệu)?|t)\\s*(\\d+)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TRAM =
            Pattern.compile("(\\d+)\\s*(?:tram|trăm)\\s*((\\d+)|ruoi|rưỡi)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TY =
            Pattern.compile("(\\d+)\\s*(?:ty|tỷ)\\s*(\\d+)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_RUOI =
            Pattern.compile("(\\d+)\\s*(k|tr(?:ieu|iệu)?|t|ty|tỷ)?\\s*(?:ruoi|rưỡi)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_K =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*k\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TR =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:tr(?:ieu|iệu)?|t)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TY_UNIT =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:ty|tỷ)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_NUM =
            Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?)\\s*(?:đ|d|vnd|vnđ)?", Pattern.CASE_INSENSITIVE);

    // ── Bộ tách các giao dịch đa tầng ──
    private static final Pattern PAT_MULTI_TX =
            Pattern.compile("[,;]\\s*(?=[a-zA-ZÀ-ỹ])");

    // ── Phát hiện từ khóa tiếng Việt ──
    private static final List<String> ENGLISH_KEYWORDS = List.of(
            "today", "yesterday", "this month", "last month", "this year", "last year",
            "delete", "remove", "update", "change", "income", "expense", "spend", "spent",
            "buy", "purchase", "how much", "total", "average");

    // ═════════════════════════════════════════════════════════
    // API CÔNG KHAI
    // ═════════════════════════════════════════════════════════

    /**
     * Đường ống tiền xử lý đầy đủ: chuẩn hóa → trích xuất số tiền → phát hiện ngày tháng → tách đa giao dịch.
     */
    public ParsedMessage preprocess(String rawMessage, String requestedLanguage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        String normalized = normalizeVietnamese(message);
        String language = detectLanguage(requestedLanguage, message);
        List<BigDecimal> amounts = extractAllAmounts(message);
        LocalDate[] dateRange = detectDateRange(normalized);
        List<String> subMessages = splitMultiTransaction(message);

        return ParsedMessage.builder()
                .originalText(message)
                .normalizedText(normalized)
                .language(language)
                .extractedAmounts(amounts)
                .startDate(dateRange[0])
                .endDate(dateRange[1])
                .subMessages(subMessages)
                .build();
    }

    // ═════════════════════════════════════════════════════════
    // CHUẨN HÓA VĂN BẢN
    // ═════════════════════════════════════════════════════════

    /** Loại bỏ dấu tiếng Việt và chuẩn hóa đ → d. */
    public String normalizeVietnamese(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}+", "");
        normalized = normalized.replace('đ', 'd');
        return normalized;
    }

    // ═════════════════════════════════════════════════════════
    // PHÁT HIỆN NGÔN NGỮ
    // ═════════════════════════════════════════════════════════

    public String detectLanguage(String requested, String message) {
        if (containsVietnameseDiacritics(message)) return "vi";
        
        if (requested != null && !requested.isBlank()) {
            String norm = requested.trim().toLowerCase(Locale.ROOT);
            if (norm.startsWith("en")) return "en";
            if (norm.startsWith("vi")) return "vi";
        }
        
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            for (String kw : ENGLISH_KEYWORDS) {
                if (lower.contains(kw)) return "en";
            }
        }
        return "vi";
    }

    private boolean containsVietnameseDiacritics(String text) {
        if (text == null || text.isBlank()) return false;
        return !normalizeVietnamese(text).equals(text.toLowerCase(Locale.ROOT));
    }

    // ═════════════════════════════════════════════════════════
    // TRÍCH XUẤT SỐ TIỀN
    // ═════════════════════════════════════════════════════════

    /**
     * Trích xuất tất cả các số tiền từ văn bản.
     * Hỗ trợ: 50k, 1tr, 1.2tr, 2 triệu, 200 nghìn, 50.000đ, hai trăm rưỡi, v.v.
     */
    public List<BigDecimal> extractAllAmounts(String text) {
        if (text == null || text.isBlank()) return List.of();
        // Đối với các tin nhắn chứa nhiều giao dịch, thực hiện trích xuất từ mỗi tin nhắn con
        List<String> parts = splitMultiTransaction(text);
        if (parts.size() > 1) {
            List<BigDecimal> results = new ArrayList<>();
            for (String part : parts) {
                BigDecimal amt = extractSingleAmount(part);
                if (amt != null) results.add(amt);
            }
            return results;
        }
        BigDecimal single = extractSingleAmount(text);
        return single != null ? List.of(single) : List.of();
    }

    /**
     * Trích xuất một số tiền duy nhất từ văn bản. Trả về null nếu không tìm thấy số tiền nào.
     */
    public BigDecimal extractSingleAmount(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT).trim();

        // 1. "X triệu Y" (VD: "1 triệu 2" → 1.200.000)
        Matcher m = PAT_TR_MIXED.matcher(lower);
        if (m.find()) {
            BigDecimal main = new BigDecimal(m.group(1)).multiply(BD_MILLION);
            String sub = m.group(2);
            if (sub != null && !sub.isEmpty()) {
                BigDecimal subVal = new BigDecimal(sub);
                if (subVal.compareTo(BD_THOUSAND) < 0) {
                    subVal = multiplyBySubDigitPlace(subVal, sub.length(), BD_HUNDRED_K, BD_TEN_K, BD_THOUSAND);
                }
                return main.add(subVal);
            }
            return main;
        }

        // 2. "X trăm Y" (VD: "2 trăm rưỡi" → 250.000 trong ngữ cảnh tài chính VN)
        m = PAT_TRAM.matcher(lower);
        if (m.find()) {
            BigDecimal main = new BigDecimal(m.group(1)).multiply(BD_HUNDRED_K);
            String group2 = m.group(2);
            if (group2 != null && (group2.contains("ruoi") || group2.contains("rưỡi"))) {
                return main.add(BD_FIFTY_K);
            }
            String sub = m.group(3);
            if (sub != null && !sub.isEmpty()) {
                BigDecimal subVal = new BigDecimal(sub);
                if (subVal.compareTo(BD_HUNDRED) < 0) {
                    subVal = multiplyBySubDigitPlace(subVal, sub.length(), BD_TEN_K, BD_THOUSAND, BigDecimal.ONE);
                }
                return main.add(subVal);
            }
            return main;
        }

        // 3. "X tỷ Y" (VD: "1 tỷ 2" → 1.200.000.000)
        m = PAT_TY.matcher(lower);
        if (m.find()) {
            BigDecimal main = new BigDecimal(m.group(1)).multiply(BD_BILLION);
            String sub = m.group(2);
            if (sub != null && !sub.isEmpty()) {
                BigDecimal subVal = new BigDecimal(sub);
                if (subVal.compareTo(BD_THOUSAND) < 0) {
                    subVal = multiplyBySubDigitPlace(subVal, sub.length(),
                            new BigDecimal("100000000"), new BigDecimal("10000000"), BD_MILLION);
                }
                return main.add(subVal);
            }
            return main;
        }

        // 4. "X rưỡi" (VD: "5k rưỡi" → 5.500)
        if (lower.contains(" rưỡi") || lower.contains(" ruoi") || lower.contains("rưỡi") || lower.contains("ruoi")) {
            m = PAT_RUOI.matcher(lower);
            if (m.find()) {
                BigDecimal main = new BigDecimal(m.group(1));
                String unit = m.group(2);
                if (unit == null || unit.isEmpty() || unit.equalsIgnoreCase("k")) {
                    return main.multiply(BD_THOUSAND).add(new BigDecimal("500"));
                } else if (unit.matches("(?i)tr(?:ieu|iệu)?|t")) {
                    return main.multiply(BD_MILLION).add(new BigDecimal("500000"));
                } else if (unit.matches("(?i)ty|tỷ")) {
                    return main.multiply(BD_BILLION).add(new BigDecimal("500000000"));
                }
            }
        }

        // 5. Các đơn vị chuẩn: k, tr, tỷ
        m = PAT_K.matcher(lower);
        if (m.find()) {
            BigDecimal num = parseDecimal(m.group(1));
            return num != null ? num.multiply(BD_THOUSAND) : null;
        }
        m = PAT_TR.matcher(lower);
        if (m.find()) {
            BigDecimal num = parseDecimal(m.group(1));
            return num != null ? num.multiply(BD_MILLION) : null;
        }
        m = PAT_TY_UNIT.matcher(lower);
        if (m.find()) {
            BigDecimal num = parseDecimal(m.group(1));
            return num != null ? num.multiply(BD_BILLION) : null;
        }

        // 6. Số thuần túy với hậu tố tiền tệ tùy chọn
        m = PAT_NUM.matcher(lower);
        if (m.find()) {
            String raw = m.group(1);
            String cleaned = raw.replaceAll("[.,]", "");
            try {
                BigDecimal val = new BigDecimal(cleaned);
                // Suy nghiệm: các số nhỏ trong ngữ cảnh ăn uống thường có nghĩa là hàng nghìn
                if (val.compareTo(BD_THOUSAND) < 0 && containsFoodContext(lower)) {
                    return val.multiply(BD_THOUSAND);
                }
                return val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════
    // PHÁT HIỆN NGÀY THÁNG
    // ═════════════════════════════════════════════════════════

    /**
     * Phát hiện khoảng ngày từ văn bản đã chuẩn hóa.
     * Trả về mảng [bắt đầu, kết thúc]. Cả hai có thể là null nếu không phát hiện được ngày nào.
     */
    public LocalDate[] detectDateRange(String normalizedText) {
        if (normalizedText == null) return new LocalDate[]{null, null};
        LocalDate today = LocalDate.now();

        // 1. Phát hiện năm cụ thể
        Matcher yearMatcher = Pattern.compile("\\b(nam|year)\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE).matcher(normalizedText);
        Integer forcedYear = null;
        if (yearMatcher.find()) {
            forcedYear = Integer.parseInt(yearMatcher.group(2));
        }

        // 2. Phát hiện tháng cụ thể (VD: "thang 4", "thang 12", "month 6")
        Matcher monthMatcher = Pattern.compile("\\b(thang|month|t)\\s*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE).matcher(normalizedText);
        if (monthMatcher.find()) {
            int month = Integer.parseInt(monthMatcher.group(2));
            if (month >= 1 && month <= 12) {
                int year = forcedYear != null ? forcedYear : today.getYear();
                // Nếu tháng thuộc năm sau (VD: hôm nay là tháng 12, người dùng nói tháng 1)
                if (forcedYear == null && month < today.getMonthValue() && today.getMonthValue() >= 10) {
                    year++;
                }
                LocalDate start = LocalDate.of(year, month, 1);
                return new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
            }
        }

        // Hôm nay
        if (containsAny(normalizedText, "hom nay", "vua nay", "vua moi", "chieu nay",
                "sang nay", "toi nay", "today", "this morning", "this afternoon",
                "this evening", "tonight")) {
            return new LocalDate[]{today, today};
        }
        // Hôm qua
        if (normalizedText.contains("hom qua") || normalizedText.contains("yesterday")) {
            LocalDate d = today.minusDays(1);
            return new LocalDate[]{d, d};
        }
        // Hôm kia
        if (normalizedText.contains("hom kia")) {
            LocalDate d = today.minusDays(2);
            return new LocalDate[]{d, d};
        }
        // Tuần này
        if (normalizedText.contains("tuan nay") || normalizedText.contains("this week")) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new LocalDate[]{start, today};
        }
        // Tuần trước
        if (normalizedText.contains("tuan truoc") || normalizedText.contains("last week")) {
            LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = weekStart.minusDays(1);
            LocalDate start = end.minusDays(6);
            return new LocalDate[]{start, end};
        }
        // Tháng này
        if (containsAny(normalizedText, "thang nay", "this month", "current month")) {
            return new LocalDate[]{today.withDayOfMonth(1), today};
        }
        // Tháng trước
        if (containsAny(normalizedText, "thang truoc", "last month", "previous month")) {
            LocalDate prev = today.minusMonths(1);
            return new LocalDate[]{prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth())};
        }
        // Tháng sau
        if (containsAny(normalizedText, "thang sau", "thang toi", "next month")) {
            LocalDate next = today.plusMonths(1);
            return new LocalDate[]{next.withDayOfMonth(1), next.withDayOfMonth(next.lengthOfMonth())};
        }
        // Năm nay
        if (normalizedText.contains("nam nay") || normalizedText.contains("this year")) {
            return new LocalDate[]{LocalDate.of(today.getYear(), 1, 1), today};
        }
        // Năm ngoái
        if (containsAny(normalizedText, "nam ngoai", "last year", "previous year")) {
            int year = today.getYear() - 1;
            return new LocalDate[]{LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
        }
        return new LocalDate[]{null, null};
    }

    /**
     * Phát hiện một ngày duy nhất từ văn bản. Trả về fallback nếu không tìm thấy.
     */
    public LocalDate detectSingleDate(String text, LocalDate fallback) {
        if (text == null) return fallback;
        String normalized = normalizeVietnamese(text);
        LocalDate[] range = detectDateRange(normalized);
        return range[0] != null ? range[0] : fallback;
    }

    // ═════════════════════════════════════════════════════════
    // TÁCH GIAO DỊCH ĐA TẦNG
    // ═════════════════════════════════════════════════════════

    /**
     * Tách một tin nhắn chứa nhiều giao dịch thành các tin nhắn con riêng lẻ.
     * "sáng ăn phở 45k, cà phê 30k" → ["sáng ăn phở 45k", "cà phê 30k"]
     */
    public List<String> splitMultiTransaction(String message) {
        if (message == null || message.isBlank()) return List.of(message != null ? message : "");
        String[] parts = PAT_MULTI_TX.split(message.trim());
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result.isEmpty() ? List.of(message.trim()) : result;
    }

    // ═════════════════════════════════════════════════════════
    // PHƯƠNG THỨC HỖ TRỢ CHI TIẾT
    // ═════════════════════════════════════════════════════════

    private static final BigDecimal BD_THOUSAND = new BigDecimal("1000");
    private static final BigDecimal BD_TEN_K = new BigDecimal("10000");
    private static final BigDecimal BD_FIFTY_K = new BigDecimal("50000");
    private static final BigDecimal BD_HUNDRED_K = new BigDecimal("100000");
    private static final BigDecimal BD_HUNDRED = new BigDecimal("100");
    private static final BigDecimal BD_MILLION = new BigDecimal("1000000");
    private static final BigDecimal BD_BILLION = new BigDecimal("1000000000");

    private static BigDecimal multiplyBySubDigitPlace(BigDecimal sub, int digitLen,
                                                       BigDecimal one, BigDecimal two, BigDecimal three) {
        if (digitLen == 1) return sub.multiply(one);
        if (digitLen == 2) return sub.multiply(two);
        if (digitLen == 3) return sub.multiply(three);
        return sub;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null) return null;
        String normalized = raw.replace(",", ".");
        if (normalized.chars().filter(ch -> ch == '.').count() > 1) {
            normalized = normalized.replace(".", "");
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsFoodContext(String lower) {
        return lower.contains("ăn") || lower.contains("an ") || lower.contains("uống")
                || lower.contains("uong") || lower.contains("phở") || lower.contains("pho")
                || lower.contains("cơm") || lower.contains("com") || lower.contains("mì")
                || lower.contains("mi ");
    }

    public static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    public static boolean containsAny(String text, List<String> keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
