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

    /**
     * Builds a localized prompt for Gemini.
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

                OUTPUT REQUIREMENTS:
                - Return ONLY pure JSON, no markdown, no extra explanation.
                - Use YYYY-MM-DD date format.
                - Identify intent: INSERT (add transaction), QUERY (look up), UPDATE (edit), DELETE (remove), ADVICE (general chat/advice), SET_BUDGET (set an expense budget limit), SET_INCOME_TARGET (set an income target/goal).
                - For SET_BUDGET: use entries[0] for categoryName and limit amount. Category MUST be EXPENSE type.
                - For SET_INCOME_TARGET: use entries[0] for categoryName and target amount. Category MUST be INCOME type.
                - IMPORTANT: "Budget" / "Expense Limit" = SET_BUDGET. "Goal" / "Income Target" = SET_INCOME_TARGET.
                - If the user says "set a target" or "set a budget" without amount/category, still select the corresponding intent and leave fields empty. DO NOT select ADVICE for these cases.
                - DO NOT fill "adviceReply" if intent is SET_BUDGET or SET_INCOME_TARGET.
                - %s

                SCHEMA:
                {
                  "intent": "QUERY" | "INSERT" | "UPDATE" | "DELETE" | "ADVICE" | "SET_BUDGET" | "SET_INCOME_TARGET" | "UNKNOWN",
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
                - "Set budget for food 5M": intent=SET_BUDGET, entries=[{amount: 5000000, categoryName: "Ăn uống"}]
                - "Set income goal for salary 20M": intent=SET_INCOME_TARGET, entries=[{amount: 20000000, categoryName: "Lương"}]
                - "How can I save?": intent=ADVICE, adviceReply="..."

                INPUT:
                %s
                """.formatted(langName, today.format(DATE_FMT), categories, history, adviceInstr, message);
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
