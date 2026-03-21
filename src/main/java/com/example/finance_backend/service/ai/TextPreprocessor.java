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
 * Text pre-processing pipeline component.
 * Handles: text normalization, money extraction, date detection,
 * multi-transaction splitting, and language detection.
 */
@Component
public class TextPreprocessor {

    // ── Money patterns (order matters: most specific first) ──
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

    // ── Multi-transaction splitters ──
    private static final Pattern PAT_MULTI_TX =
            Pattern.compile("[,;]\\s*(?=[a-zA-ZÀ-ỹ])");

    // ── Vietnamese keyword detection ──
    private static final List<String> ENGLISH_KEYWORDS = List.of(
            "today", "yesterday", "this month", "last month", "this year", "last year",
            "delete", "remove", "update", "change", "income", "expense", "spend", "spent",
            "buy", "purchase", "how much", "total", "average");

    // ═════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════

    /**
     * Full preprocessing pipeline: normalize → extract amounts → detect dates → split multi-tx.
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
    // TEXT NORMALIZATION
    // ═════════════════════════════════════════════════════════

    /** Removes Vietnamese diacritics and normalizes đ → d. */
    public String normalizeVietnamese(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}+", "");
        normalized = normalized.replace('đ', 'd');
        return normalized;
    }

    // ═════════════════════════════════════════════════════════
    // LANGUAGE DETECTION
    // ═════════════════════════════════════════════════════════

    public String detectLanguage(String requested, String message) {
        if (requested != null && !requested.isBlank()) {
            String norm = requested.trim().toLowerCase(Locale.ROOT);
            if (norm.startsWith("en")) return "en";
            if (norm.startsWith("vi")) return "vi";
        }
        if (containsVietnameseDiacritics(message)) return "vi";
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
    // MONEY EXTRACTION
    // ═════════════════════════════════════════════════════════

    /**
     * Extracts all monetary amounts from the text.
     * Supports: 50k, 1tr, 1.2tr, 2 triệu, 200 nghìn, 50.000đ, hai trăm rưỡi, etc.
     */
    public List<BigDecimal> extractAllAmounts(String text) {
        if (text == null || text.isBlank()) return List.of();
        // For multi-transaction messages, extract from each sub-message
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
     * Extracts a single monetary amount from text. Returns null if no amount found.
     */
    public BigDecimal extractSingleAmount(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT).trim();

        // 1. "X triệu Y" (e.g., "1 triệu 2" → 1,200,000)
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

        // 2. "X trăm Y" (e.g., "2 trăm rưỡi" → 250,000 in VN finance context)
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

        // 3. "X tỷ Y" (e.g., "1 tỷ 2" → 1,200,000,000)
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

        // 4. "X rưỡi" (e.g., "5k rưỡi" → 5,500)
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

        // 5. Standard units: k, tr, tỷ
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

        // 6. Plain number with optional currency suffix
        m = PAT_NUM.matcher(lower);
        if (m.find()) {
            String raw = m.group(1);
            String cleaned = raw.replaceAll("[.,]", "");
            try {
                BigDecimal val = new BigDecimal(cleaned);
                // Heuristic: small numbers in food context likely mean thousands
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
    // DATE DETECTION
    // ═════════════════════════════════════════════════════════

    /**
     * Detects date range from normalized text.
     * Returns [start, end] array. Both may be null if no date detected.
     */
    public LocalDate[] detectDateRange(String normalizedText) {
        if (normalizedText == null) return new LocalDate[]{null, null};
        LocalDate today = LocalDate.now();

        // Today
        if (containsAny(normalizedText, "hom nay", "vua nay", "vua moi", "chieu nay",
                "sang nay", "toi nay", "today", "this morning", "this afternoon",
                "this evening", "tonight")) {
            return new LocalDate[]{today, today};
        }
        // Yesterday
        if (normalizedText.contains("hom qua") || normalizedText.contains("yesterday")) {
            LocalDate d = today.minusDays(1);
            return new LocalDate[]{d, d};
        }
        // Day before yesterday
        if (normalizedText.contains("hom kia")) {
            LocalDate d = today.minusDays(2);
            return new LocalDate[]{d, d};
        }
        // This week
        if (normalizedText.contains("tuan nay") || normalizedText.contains("this week")) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new LocalDate[]{start, today};
        }
        // Last week
        if (normalizedText.contains("tuan truoc") || normalizedText.contains("last week")) {
            LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = weekStart.minusDays(1);
            LocalDate start = end.minusDays(6);
            return new LocalDate[]{start, end};
        }
        // This month
        if (containsAny(normalizedText, "thang nay", "this month", "current month")) {
            return new LocalDate[]{today.withDayOfMonth(1), today};
        }
        // Last month
        if (containsAny(normalizedText, "thang truoc", "last month", "previous month")) {
            LocalDate prev = today.minusMonths(1);
            return new LocalDate[]{prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth())};
        }
        // This year
        if (normalizedText.contains("nam nay") || normalizedText.contains("this year")) {
            return new LocalDate[]{LocalDate.of(today.getYear(), 1, 1), today};
        }
        // Last year
        if (containsAny(normalizedText, "nam ngoai", "last year", "previous year")) {
            int year = today.getYear() - 1;
            return new LocalDate[]{LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
        }
        return new LocalDate[]{null, null};
    }

    /**
     * Detects a single date from text. Returns fallback if none found.
     */
    public LocalDate detectSingleDate(String text, LocalDate fallback) {
        if (text == null) return fallback;
        String normalized = normalizeVietnamese(text);
        LocalDate[] range = detectDateRange(normalized);
        return range[0] != null ? range[0] : fallback;
    }

    // ═════════════════════════════════════════════════════════
    // MULTI-TRANSACTION SPLITTING
    // ═════════════════════════════════════════════════════════

    /**
     * Splits a multi-transaction message into individual sub-messages.
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
    // UTILITY HELPERS
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
