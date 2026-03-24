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
 * Trình bao bọc Gemini API riêng biệt. Xử lý việc xây dựng prompt, gọi API,
 * phân tích phản hồi, và hỗ trợ đa phương thức (hình ảnh).
 * Không bao giờ để người dùng thấy các lỗi API thô.
 */
@Component
public class GeminiClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(GeminiClientWrapper.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    @Value("${ai.gemini.model:gemini-flash-latest}")
    private String model;

    @Value("${ai.gemini.api-key:}")
    private String apiKeyFromProps;

    private Client client;

    public GeminiClientWrapper(CategoryService categoryService, ObjectMapper objectMapper) {
        this.categoryService = categoryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Phân tích tin nhắn của người dùng bằng Gemini API. Trả về kết quả phân tích
     * có cấu trúc.
     * Trả về null nếu phân tích thất bại (người gọi nên sử dụng dự phòng dựa trên
     * quy tắc).
     */
    public GeminiParseResult parse(String message, String base64Image,
            List<AiMessage> history, String language) {
        try {
            LocalDate today = LocalDate.now();
            String categoriesStr = categoryService.findAll().stream()
                    .map(c -> c.getName())
                    .collect(Collectors.joining(", "));
            String historyBlock = formatHistory(history, language);

            String prompt = buildPrompt(language, today, categoriesStr, historyBlock, message);
            GenerateContentResponse response;

            if (base64Image != null && !base64Image.isBlank()) {
                response = callMultimodal(prompt, base64Image);
            } else {
                response = getClient().models.generateContent(model, prompt, null);
            }

            String text = response.text();
            if (text == null)
                return null;

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
    // XÂY DỰNG PROMPT
    // ═════════════════════════════════════════════════════════

    /**
     * Xây dựng một prompt được bản địa hóa cho Gemini.
     */
    public String buildPrompt(String language, LocalDate today, String categories, String history, String message) {
        String langName = switch (language) {
            case "en" -> "English";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "zh" -> "Chinese";
            default -> "Vietnamese";
        };

        String adviceInstr = switch (language) {
            case "en" -> "If the intent is ADVICE, provide a helpful and professional response in English.";
            case "ja" -> "意図がADVICEの場合、日本語で丁寧かつ専門的な回答を提供してください。";
            case "ko" -> "의도가 ADVICE인 경우, 한국어로 친절하고 전문적인 답변을 제공하십시오。";
            case "zh" -> "如果意图是 ADVICE，请用中文提供友好且专业的回答。";
            default -> "Nếu intent là ADVICE, hãy trả lời bằng tiếng Việt một cách hữu ích và chuyên nghiệp.";
        };

        return """
                You are an AI Assistant for a Finance Management App. Analyze intent and extract data.
                Always respond in %s.
                Today is %s (Asia/Ho_Chi_Minh).
                Valid categories: %s.
                Conversation history:
                %s

                MULTIMODAL INSTRUCTIONS:
                - If an image is provided, it is likely a receipt, invoice, or bill.
                - Analyze the image to extract all transaction details (amount, category, note/items, date).
                - Even if the text input is empty, prioritize extracting data from the image.
                - If the image contains multiple distinct items or receipts, extract them into the "entries" array.

                OUTPUT REQUIREMENTS:
                - Return ONLY pure JSON, no markdown, no extra explanation.
                - Use YYYY-MM-DD date format.
                - Identify intent: INSERT (add transaction), QUERY (look up), UPDATE (edit), DELETE (remove), ADVICE (general chat/advice), CREATE_BUDGET (set an expense budget limit), CREATE_INCOME_GOAL (set an income target/goal), VIEW_FINANCIAL_PLAN (view budget and goals overview).
                - For CREATE_BUDGET: use entries[0] for categoryName and limit amount. Category MUST be EXPENSE type.
                - For CREATE_INCOME_GOAL: use entries[0] for categoryName and target amount. Category MUST be INCOME type.
                - "Budget" / "Expense Limit" = CREATE_BUDGET. "Goal" / "Income Target" = CREATE_INCOME_GOAL.
                - CRITICAL FOR BUDGET/GOAL: If the user explicitly mentions a month (e.g. 'tháng 6', 'tháng này', 'tháng tới', 'tháng 1'), extract it into entries[0].date in YYYY-MM-01 format. If NO month is mentioned, entries[0].date MUST be null.
                - CRITICAL FOR CATEGORY: Only set `categoryName` if it is EXPLICITLY mentioned in the user message or detectable from the image. DO NOT guess or infer a category if the user didn't specify one. If no category is mentioned, set `categoryName` to null.
                - If the intent involves an image of a purchase/payment, always set intent to "INSERT" and set isConfirmation to false.
                - If the user explicitly confirms a previously proposed transaction or financial plan (e.g., "yes", "save", "ok", "confirm", "lưu", "đồng ý"), set isConfirmation to true.
                - If the user modifies a previously proposed transaction (e.g., "change amount to 50k"), set isConfirmation to false and update the entries.
                - If the intent or message implies deleting multiple or all entries for a specific time/category (e.g., "delete all", "everything", "toàn bộ", "tất cả", "hết"), set target.deleteAll to true.
                - **PENDING TRANSACTIONS RULE**: If the conversation history contains transactions that were proposed but NOT yet confirmed/saved, and the user adds NEW transactions, APPEND the new ones and return the FULL accumulated list in the "entries" array.
                - If the user says "hủy", "xóa", "làm lại", or "bỏ giao dịch trước", set intent to "DELETE" and "target.deleteAll" to true to indicate a request to discard the pending list.
                - %s

                SCHEMA:
                {
                  "intent": "QUERY" | "INSERT" | "UPDATE" | "DELETE" | "ADVICE" | "CREATE_BUDGET" | "CREATE_INCOME_GOAL" | "VIEW_FINANCIAL_PLAN" | "UNKNOWN",
                  "isConfirmation": boolean,
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

                EXAMPLES:
                - [Image and "Set budget for food 5M"]: intent=CREATE_BUDGET, entries=[{amount: 5000000, categoryName: "Ăn uống", date: null}], isConfirmation=false
                - "ngân sách ăn uống 3 triệu tháng 6": intent=CREATE_BUDGET, entries=[{amount: 3000000, categoryName: "Ăn uống", date: "YYYY-06-01"}], isConfirmation=false
                - "mục tiêu lương 20 triệu tháng này": intent=CREATE_INCOME_GOAL, entries=[{amount: 20000000, categoryName: "Lương", date: "YYYY-MM-01"}], isConfirmation=false
                - [Receipt Image of a 50k Coffee]: intent=INSERT, entries=[{amount: 50000, categoryName: "Ăn uống", note: "Coffee"}], isConfirmation=false
                - "Yes, save it" (after a 50k Coffee was proposed): intent=INSERT, entries=[{amount: 50000, ...}], isConfirmation=true
                - "ok lưu ngân sách" (after a budget was proposed): intent=CREATE_BUDGET, entries=[{amount: 3000000, ...}], isConfirmation=true
                - "xem kế hoạch tài chính": intent=VIEW_FINANCIAL_PLAN, isConfirmation=false
                - "How can I save?": intent=ADVICE, adviceReply="...", isConfirmation=false

                INPUT:
                %s
                """
                .formatted(langName, today.format(DATE_FMT), categories, history, adviceInstr, message);
    }

    // ═════════════════════════════════════════════════════════
    // PHƯƠNG THỨC HỖ TRỢ API
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
                        .data(java.util.Base64.getDecoder().decode(data)) // Giải mã Base64 thành byte[]
                        .mimeType(mimeType)
                        .build())
                .build();

        Content content = Content.builder()
                .parts(List.of(textPart, imagePart))
                .build();

        return getClient().models.generateContent(model, List.of(content), null);
    }

    private Client getClient() {
        if (client != null)
            return client;

        String apiKey = apiKeyFromProps;
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GOOGLE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }

        if (apiKey != null && !apiKey.isBlank()) {
            log.info("Initializing Gemini Client with API Key (from props or env)");
            client = Client.builder().apiKey(apiKey).build();
        } else {
            log.warn("Gemini API Key is MISSING. Falling back to guest mode which might fail.");
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
    // DTO PHẢN HỒI (được sử dụng để giải mã JSON)
    // ═════════════════════════════════════════════════════════

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParseResult {
        public String intent;
        public Boolean isConfirmation;
        public String adviceReply;
        public GeminiParsedQuery query;
        public GeminiParsedTarget target;
        public List<GeminiParsedEntry> entries;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedTarget {
        public BigDecimal amount;
        public String categoryName;
        public String date;
        public String noteKeywords;
        public Boolean deleteAll;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedQuery {
        public String metric;
        public String type;
        public String startDate;
        public String endDate;
        public Integer limit;
        public String categoryName;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedEntry {
        public BigDecimal amount;
        public String categoryName;
        public String note;
        public String type;
        public String date;
    }
}
