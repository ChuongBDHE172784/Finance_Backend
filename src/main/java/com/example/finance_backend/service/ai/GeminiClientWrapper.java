package com.example.finance_backend.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.service.CategoryService;
import com.google.genai.Client;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Isolated Gemini API wrapper. Handles prompt construction, API calls,
 * response parsing, and multimodal (image) support.
 * Never exposes raw API errors to users.
 */
@Component
public class GeminiClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(GeminiClientWrapper.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    @Value("${ai.gemini.model:gemini-flash-latest}")
    private String model;

    private Client client;

    public GeminiClientWrapper(CategoryService categoryService, ObjectMapper objectMapper) {
        this.categoryService = categoryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a user message using Gemini API. Returns the structured parse result.
     * Returns null if parsing fails (caller should use rule-based fallback).
     */
    public GeminiParseResult parse(String message, String base64Image,
                                    List<AiMessage> history, String language) {
        try {
            String prompt = buildPrompt(message, history, language);
            GenerateContentResponse response;

            if (base64Image != null && !base64Image.isBlank()) {
                response = callMultimodal(prompt, base64Image);
            } else {
                response = getClient().models.generateContent(model, prompt, null);
            }

            String text = response.text();
            if (text == null) return null;

            String json = extractJson(text);
            ObjectMapper mapper = objectMapper.copy();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return mapper.readValue(json, GeminiParseResult.class);
        } catch (Exception e) {
            log.warn("Gemini parse failed for message: {}", message, e);
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════
    // PROMPT CONSTRUCTION
    // ═════════════════════════════════════════════════════════

    private String buildPrompt(String message, List<AiMessage> history, String language) {
        String safeMessage = message == null ? "" : message.trim();
        LocalDate today = LocalDate.now();
        String categoriesStr = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));
        String historyBlock = formatHistory(history, language);

        if ("en".equals(language)) {
            return buildEnglishPrompt(today, categoriesStr, historyBlock, safeMessage);
        }
        return buildVietnamesePrompt(today, categoriesStr, historyBlock, safeMessage);
    }

    private String buildEnglishPrompt(LocalDate today, String categories, String history, String message) {
        return """
                You are an AI assistant for a personal finance app. Analyze the user's intent and extract data.
                Today is %s (Asia/Ho_Chi_Minh).
                Valid categories: %s.
                Conversation history (chronological, role USER/ASSISTANT):
                %s

                OUTPUT REQUIREMENTS:
                - Return raw JSON only, no markdown or extra text.
                - Always use YYYY-MM-DD date format.
                - Determine intent: INSERT (add transaction), QUERY (look up), UPDATE (edit), DELETE (remove), ADVICE (general chat/advice), SET_BUDGET (set a target budget limit).
                - For SET_BUDGET, use entries[0] to specify categoryName and amount.
                - If ADVICE, fill "adviceReply" with an English answer.
                - For UPDATE/DELETE, use "target" to identify the transaction (amount, date, old category). Use "entries[0]" for new values (if UPDATE).
                - If the user wants to delete all transactions in a time range/condition, set "target.deleteAll = true".
                - If the user uses English category names, map them to the closest category from the list above and output that category name exactly.
                - If a receipt/invoice image is provided, perform OCR and summarize all findings (date, items, total). Ask the user if they want to add these transactions. Set intent to ADVICE and provide the summary in "adviceReply".
                - If the user asks to CORRECT or CHANGE any detail of the receipt they just sent (before saving), do NOT use intent UPDATE. Instead, update your summary and ask if it's correct now. Stay in intent ADVICE.
                - ONLY use intent UPDATE when the user wants to change a transaction that was ALREADY saved to the database in the past.
                - Do NOT use intent INSERT directly from an image unless the user specifically says "Add this" or "Save this".

                SCHEMA:
                {
                  "intent": "QUERY" | "INSERT" | "UPDATE" | "DELETE" | "ADVICE" | "SET_BUDGET" | "UNKNOWN",
                  "adviceReply": "string (only for ADVICE)",
                  "query": {
                    "metric": "TOTAL" | "TOP_CATEGORY" | "LIST" | "AVERAGE" | "TREND" | "PERCENTAGE" | "BUDGET" | "MONTHLY_SUMMARY" | "FINANCIAL_HEALTH" | "WEEKLY_PATTERN" | "SMART_SUGGESTION" | "FINANCIAL_SCORE",
                    "type": "EXPENSE" | "INCOME" | "ALL",
                    "startDate": "YYYY-MM-DD",
                    "endDate": "YYYY-MM-DD",
                    "limit": 10,
                    "categoryName": "string"
                  },
                  "target": {
                    "amount": 45000,
                    "categoryName": "Ăn uống",
                    "date": "YYYY-MM-DD",
                    "noteKeywords": "pho",
                    "deleteAll": false
                  },
                  "entries": [
                    {
                      "amount": 45000,
                      "categoryName": "Ăn uống",
                      "note": "Pho",
                      "type": "EXPENSE",
                      "date": "YYYY-MM-DD"
                    }
                  ]
                }

                SET_BUDGET EXAMPLE:
                - "Set budget for food 5M": intent=SET_BUDGET, entries=[{amount: 5000000, categoryName: "Ăn uống"}]
                - "Eat phở 5M": intent=INSERT (because it's an action, not a goal)

                ADVANCED QUERY EXAMPLES:
                - "Average daily spending this month": metric=AVERAGE
                - "Did my spending increase compared to last month": metric=TREND
                - "Spending by category percentage": metric=PERCENTAGE
                - "How's my budget?": metric=BUDGET
                - "Monthly summary": metric=MONTHLY_SUMMARY
                - "My financial health": metric=FINANCIAL_HEALTH
                - "Do I spend more on weekends?": metric=WEEKLY_PATTERN
                - "Any saving suggestions?": metric=SMART_SUGGESTION
                - "What's my financial score?": metric=FINANCIAL_SCORE

                ADVICE EXAMPLE:
                - "How can I save money?": intent=ADVICE, adviceReply="To save money, you should..."

                INPUT:
                %s
                """.formatted(today.format(DATE_FMT), categories, history, message);
    }

    private String buildVietnamesePrompt(LocalDate today, String categories, String history, String message) {
        return """
                Bạn là trợ lý AI cho ứng dụng quản lý chi tiêu. Hãy phân tích ý định và trích xuất dữ liệu.
                Hôm nay là %s (Asia/Ho_Chi_Minh).
                Danh sách danh mục hợp lệ: %s.
                Lịch sử hội thoại (theo thời gian, vai trò USER/ASSISTANT):
                %s

                YÊU CẦU ĐẦU RA:
                - Chỉ trả về JSON thuần, không markdown, không giải thích thêm.
                - Luôn dùng định dạng ngày YYYY-MM-DD.
                - Xác định intent: INSERT (thêm giao dịch), QUERY (tra cứu), UPDATE (sửa), DELETE (xóa), ADVICE (tư vấn/hỏi đáp chung), SET_BUDGET (đặt hạn mức ngân sách).
                - Với SET_BUDGET, dùng entries[0] để chỉ định categoryName và số tiền hạn mức.
                - Nếu là ADVICE, hãy điền câu trả lời vào trường "adviceReply".
                - Khi UPDATE/DELETE, dùng "target" để xác định giao dịch cần tác động. Dùng "entries[0]" cho thông tin mới (nếu UPDATE).
                - Nếu muốn xóa tất cả, đặt "target.deleteAll = true".
                - Nếu người dùng gửi ảnh hóa đơn, thực hiện OCR, tóm tắt, hỏi có muốn thêm không. Intent = ADVICE.
                - Nếu sửa thông tin hóa đơn chưa lưu, giữ intent ADVICE, KHÔNG dùng UPDATE.
                - CHỈ dùng UPDATE khi sửa giao dịch ĐÃ LƯU.
                - KHÔNG dùng INSERT trực tiếp từ ảnh trừ khi người dùng nói "Thêm" hoặc "Lưu".

                SCHEMA:
                {
                  "intent": "QUERY" | "INSERT" | "UPDATE" | "DELETE" | "ADVICE" | "SET_BUDGET" | "UNKNOWN",
                  "adviceReply": "string (chỉ dùng cho ADVICE)",
                  "query": {
                    "metric": "TOTAL" | "TOP_CATEGORY" | "LIST" | "AVERAGE" | "TREND" | "PERCENTAGE" | "BUDGET" | "MONTHLY_SUMMARY" | "FINANCIAL_HEALTH" | "WEEKLY_PATTERN" | "SMART_SUGGESTION" | "FINANCIAL_SCORE",
                    "type": "EXPENSE" | "INCOME" | "ALL",
                    "startDate": "YYYY-MM-DD",
                    "endDate": "YYYY-MM-DD",
                    "limit": 10,
                    "categoryName": "string"
                  },
                  "target": {
                    "amount": 45000,
                    "categoryName": "Ăn uống",
                    "date": "YYYY-MM-DD",
                    "noteKeywords": "phở",
                    "deleteAll": false
                  },
                  "entries": [
                    {
                      "amount": 45000,
                      "categoryName": "Ăn uống",
                      "note": "Ăn phở",
                      "type": "EXPENSE",
                      "date": "YYYY-MM-DD"
                    }
                  ]
                }

                VÍ DỤ SET_BUDGET:
                - "Hạn mức ăn uống 5 triệu": intent=SET_BUDGET, entries=[{amount: 5000000, categoryName: "Ăn uống"}]
                - "Ăn phở 5 triệu": intent=INSERT (vì đây là hành động tiêu phí, không phải đặt mục tiêu)

                VÍ DỤ UPDATE/DELETE:
                - "Sửa khoản ăn trưa hôm qua thành 50k": intent=UPDATE, target={noteKeywords: "ăn trưa", date: "hôm qua"}, entries=[{amount: 50000}]
                - "Xóa giao dịch 45k": intent=DELETE, target={amount: 45000}
                - "Xóa tất cả giao dịch hôm nay": intent=DELETE, target={date: "hôm nay", deleteAll: true}
                - "Xóa giao dịch đổ xăng 50k": intent=DELETE, target={amount: 50000, noteKeywords: "đổ xăng"}

                VÍ DỤ QUERY NÂNG CAO:
                - "Trung bình mỗi ngày tiêu bao nhiêu": metric=AVERAGE
                - "Chi phí tháng này tăng hay giảm": metric=TREND
                - "Tỷ lệ chi tiêu các nhóm": metric=PERCENTAGE
                - "Ngân sách ăn uống còn bao nhiêu": metric=BUDGET
                - "Tóm tắt tháng này": metric=MONTHLY_SUMMARY
                - "Sức khỏe tài chính": metric=FINANCIAL_HEALTH
                - "Cuối tuần tiêu nhiều hơn không": metric=WEEKLY_PATTERN
                - "Gợi ý tiết kiệm": metric=SMART_SUGGESTION
                - "Chấm điểm tài chính": metric=FINANCIAL_SCORE

                VÍ DỤ ADVICE:
                - "Làm sao tiết kiệm tiền?": intent=ADVICE, adviceReply="Để tiết kiệm tiền, bạn nên..."

                ĐẦU VÀO:
                %s
                """.formatted(today.format(DATE_FMT), categories, history, message);
    }

    // ═════════════════════════════════════════════════════════
    // API HELPERS
    // ═════════════════════════════════════════════════════════

    private GenerateContentResponse callMultimodal(String prompt, String base64Image) throws Exception {
        String data = base64Image;
        String mimeType = "image/jpeg";
        if (data.contains("base64,")) {
            String[] parts = data.split("base64,");
            data = parts[1];
            if (parts[0].contains(":")) {
                mimeType = parts[0].substring(parts[0].indexOf(":") + 1, parts[0].indexOf(";"));
            }
        }

        Part textPart = Part.builder().text(prompt).build();
        Part imagePart = Part.builder()
                .inlineData(Blob.builder()
                        .data(java.util.Base64.getDecoder().decode(data))
                        .mimeType(mimeType)
                        .build())
                .build();

        Content content = Content.builder()
                .parts(List.of(textPart, imagePart))
                .build();

        return getClient().models.generateContent(model, List.of(content), null);
    }

    private Client getClient() {
        if (client != null) return client;
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            client = Client.builder().apiKey(apiKey).build();
        } else {
            client = new Client();
        }
        return client;
    }

    private static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private static String formatHistory(List<AiMessage> history, String language) {
        if (history == null || history.isEmpty()) {
            return "en".equals(language) ? "None." : "Không có.";
        }
        StringBuilder sb = new StringBuilder();
        for (AiMessage m : history) {
            String role = m.getRole() != null ? m.getRole().toUpperCase(Locale.ROOT) : "USER";
            String content = m.getContent() != null ? m.getContent() : "";
            sb.append(role).append(": ").append(content).append("\n");
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════
    // RESPONSE DTOs (used for JSON deserialization)
    // ═════════════════════════════════════════════════════════

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParseResult {
        public String intent;
        public String adviceReply;
        public GeminiParsedQuery query;
        public GeminiParsedTarget target;
        public List<GeminiParsedEntry> entries;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedTarget {
        public BigDecimal amount;
        public String categoryName;
        public String date;
        public String noteKeywords;
        public Boolean deleteAll;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedQuery {
        public String metric;
        public String type;
        public String startDate;
        public String endDate;
        public Integer limit;
        public String categoryName;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedEntry {
        public BigDecimal amount;
        public String categoryName;
        public String note;
        public String type;
        public String date;
    }
}
