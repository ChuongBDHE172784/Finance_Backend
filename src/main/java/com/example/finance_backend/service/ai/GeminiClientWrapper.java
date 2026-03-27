package com.example.finance_backend.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.service.CategoryService;
import com.google.genai.Client;
import com.google.genai.types.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.stream.Collectors;

@Component
@Slf4j
public class GeminiClientWrapper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    @Value("${ai.gemini.model:gemini-flash-latest}")
    private String model;

    @Value("${ai.gemini.api-key:}")
    private String apiKeyFromProps;

    public GeminiClientWrapper(CategoryService categoryService, ObjectMapper objectMapper) {
        this.categoryService = categoryService;
        this.objectMapper = objectMapper;
    }

    private Client getClient(String customApiKey) {
        String apiKey = (customApiKey != null && !customApiKey.isBlank()) ? customApiKey : apiKeyFromProps;
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return Client.builder().apiKey(apiKey).build();
        } else {
            return new Client();
        }
    }

    public GeminiParseResult parse(String message, String base64Image, List<AiMessage> history, String language, String customApiKey) {
        int maxRetries = 2;
        int attempt = 0;
        
        while (attempt <= maxRetries) {
            try {
                List<Content> systemContents = buildSystemInstruction(language);
                List<Content> userContents = buildContentsWithHistory(message, base64Image, history);
                List<Tool> tools = buildTools();

                GenerateContentConfig config = GenerateContentConfig.builder()
                        .systemInstruction(systemContents.get(0))
                        .tools(tools)
                        .build();

                log.info("Sending request to Gemini (Attempt {}).", attempt + 1);
                GenerateContentResponse response = getClient(customApiKey).models.generateContent(model, userContents, config);

                if (response.candidates() != null && response.candidates().isPresent() && !response.candidates().get().isEmpty()) {
                    Candidate candidate = response.candidates().get().get(0);
                    if (candidate.content() != null && candidate.content().isPresent()) {
                        Content content = candidate.content().get();
                        if (content.parts() != null && content.parts().isPresent()) {
                            for (Part part : content.parts().get()) {
                                if (part.functionCall() != null && part.functionCall().isPresent()) {
                                    return mapFunctionCallToResult(part.functionCall().get());
                                }
                            }
                            for (Part part : content.parts().get()) {
                                if (part.text() != null && part.text().isPresent() && !part.text().get().isBlank()) {
                                    GeminiParseResult res = new GeminiParseResult();
                                    res.setIntent("CHAT"); 
                                    res.setAdviceReply(part.text().get());
                                    return res;
                                }
                            }
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : "Unknown error";
                if (msg.contains("429") || msg.contains("quota")) {
                    log.warn("Gemini Rate Limit hit. Attempt {}/{}", attempt + 1, maxRetries + 1);
                    if (attempt < maxRetries) {
                        try {
                            // Exponential wait background: 1s, 2s etc.
                            Thread.sleep((long) Math.pow(2, attempt) * 1000 + (long)(Math.random() * 500));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        attempt++;
                        continue;
                    }
                    GeminiParseResult res = new GeminiParseResult();
                    res.setRateLimited(true);
                    return res;
                }
                log.error("Gemini tools parse failed: {}", msg, t);
                return null;
            }
        }
        return null; // Should not reach here
    }

    private GeminiParseResult mapFunctionCallToResult(FunctionCall call) {
        String funcName = call.name().orElse("");
        Map<String, Object> args = call.args().orElse(Map.of());
        GeminiParseResult res = new GeminiParseResult();
        
        switch (funcName) {
            case "insertTransaction":
                res.setIntent("INSERT");
                GeminiParsedEntry entry = new GeminiParsedEntry();
                if (args.containsKey("amount")) entry.setAmount(new BigDecimal(args.get("amount").toString()));
                if (args.containsKey("categoryName")) entry.setCategoryName((String) args.get("categoryName"));
                if (args.containsKey("note")) entry.setNote((String) args.get("note"));
                if (args.containsKey("type")) entry.setType((String) args.get("type"));
                if (args.containsKey("date")) entry.setDate((String) args.get("date"));
                if (args.containsKey("walletName")) entry.setWalletName((String) args.get("walletName"));
                res.setEntries(List.of(entry));
                break;
            case "updateTransaction":
                res.setIntent("UPDATE");
                GeminiParsedTarget updateTarget = new GeminiParsedTarget();
                if (args.containsKey("amount")) updateTarget.setAmount(new BigDecimal(args.get("amount").toString()));
                if (args.containsKey("noteKeywords")) updateTarget.setNoteKeywords((String) args.get("noteKeywords"));
                if (args.containsKey("date")) updateTarget.setDate((String) args.get("date"));
                if (args.containsKey("categoryName")) updateTarget.setCategoryName((String) args.get("categoryName"));
                res.setTarget(updateTarget);
                
                // Also capture new values in entries[0] if present
                GeminiParsedEntry updateData = new GeminiParsedEntry();
                if (args.containsKey("amount")) updateData.setAmount(new BigDecimal(args.get("amount").toString()));
                res.setEntries(List.of(updateData));
                break;
            case "deleteTransaction":
                res.setIntent("DELETE");
                GeminiParsedTarget delTarget = new GeminiParsedTarget();
                if (args.containsKey("deleteAll")) delTarget.setDeleteAll((Boolean) args.get("deleteAll"));
                if (args.containsKey("noteKeywords")) delTarget.setNoteKeywords((String) args.get("noteKeywords"));
                if (args.containsKey("date")) delTarget.setDate((String) args.get("date"));
                res.setTarget(delTarget);
                break;
            case "queryTransaction":
                res.setIntent("QUERY");
                GeminiParsedQuery query = new GeminiParsedQuery();
                if (args.containsKey("metric")) query.setMetric((String) args.get("metric"));
                if (args.containsKey("type")) query.setType((String) args.get("type"));
                if (args.containsKey("startDate")) query.setStartDate((String) args.get("startDate"));
                if (args.containsKey("endDate")) query.setEndDate((String) args.get("endDate"));
                if (args.containsKey("categoryName")) query.setCategoryName((String) args.get("categoryName"));
                if (args.containsKey("minAmount")) query.setMinAmount(new BigDecimal(args.get("minAmount").toString()));
                if (args.containsKey("maxAmount")) query.setMaxAmount(new BigDecimal(args.get("maxAmount").toString()));
                res.setQuery(query);
                break;
            case "createBudget":
                res.setIntent("CREATE_BUDGET");
                GeminiParsedEntry budgetEntry = new GeminiParsedEntry();
                if (args.containsKey("amount")) budgetEntry.setAmount(new BigDecimal(args.get("amount").toString()));
                if (args.containsKey("categoryName")) budgetEntry.setCategoryName((String) args.get("categoryName"));
                if (args.containsKey("date")) budgetEntry.setDate((String) args.get("date"));
                res.setEntries(List.of(budgetEntry));
                break;
            case "createIncomeGoal":
                res.setIntent("CREATE_INCOME_GOAL");
                GeminiParsedEntry goalEntry = new GeminiParsedEntry();
                if (args.containsKey("amount")) goalEntry.setAmount(new BigDecimal(args.get("amount").toString()));
                if (args.containsKey("categoryName")) goalEntry.setCategoryName((String) args.get("categoryName"));
                res.setEntries(List.of(goalEntry));
                break;
            case "createSchedule":
                res.setIntent("CREATE_SCHEDULE");
                GeminiParsedEntry schedEntry = new GeminiParsedEntry();
                if (args.containsKey("amount")) schedEntry.setAmount(new BigDecimal(args.get("amount").toString()));
                if (args.containsKey("categoryName")) schedEntry.setCategoryName((String) args.get("categoryName"));
                if (args.containsKey("repeatType")) schedEntry.setRepeatType((String) args.get("repeatType"));
                if (args.containsKey("repeatConfig")) schedEntry.setRepeatConfig((String) args.get("repeatConfig"));
                if (args.containsKey("walletName")) schedEntry.setWalletName((String) args.get("walletName"));
                res.setEntries(List.of(schedEntry));
                break;
            case "viewFinancialPlan": res.setIntent("VIEW_FINANCIAL_PLAN"); break;
            case "disableSchedule": res.setIntent("DISABLE_SCHEDULE"); break;
            case "enableSchedule": res.setIntent("ENABLE_SCHEDULE"); break;
            case "deleteSchedule": res.setIntent("DELETE_SCHEDULE"); break;
            case "listSchedules": res.setIntent("LIST_SCHEDULES"); break;
            case "getFinancialScore": res.setIntent("FINANCIAL_SCORE"); break;
            case "getMonthlySummary": res.setIntent("MONTHLY_SUMMARY"); break;
            case "explainTransactionSource": res.setIntent("EXPLAIN_TRANSACTION_SOURCE"); break;
            case "getFinancialAdvice":
                res.setIntent("ADVICE");
                if (args.containsKey("adviceReply")) res.setAdviceReply((String) args.get("adviceReply"));
                break;
            default:
                res.setIntent("UNKNOWN");
        }
        return res;
    }

    private List<Content> buildSystemInstruction(String language) {
        String categories = categoryService.findAll().stream().map(c -> c.getName()).collect(Collectors.joining(", "));
        String text = "You are a Smart Finance AI Copilot. You help users manage money, record transactions, set schedules/budgets, and provide financial advice.\n" +
                "GUIDELINES:\n" +
                "1. Multi-entry: If a user mentions multiple transactions (e.g., 'A 10k, B 20k'), process all entries.\n" +
                "2. Context: Remember previous messages. If user says 'Fix that taxi amount to 50k', call updateTransaction.\n" +
                "3. Missing Info: If mandatory fields (amount, category) are missing, ask the user instead of guessing.\n" +
                "4. Schedules: \n" +
                "   - To stop/pause a schedule, call 'disableSchedule'.\n" +
                "   - To resume a schedule, call 'enableSchedule'.\n" +
                "   - To remove a schedule, call 'deleteSchedule'.\n" +
                "   - Use 'listSchedules' ONLY if the user explicitly asks to see all schedules or is just asking 'what schedules do I have?'.\n" +
                "   - If they specify which schedule (e.g., 'rent', 'netflix'), definitely use the action tools (disable/delete).\n" +
                "Valid categories: " + categories;
        return List.of(Content.builder().parts(List.of(Part.builder().text(text).build())).role("system").build());
    }

    private List<Content> buildContentsWithHistory(String message, String base64Image, List<AiMessage> history) {
        List<Content> contents = new ArrayList<>();
        if (history != null) {
            for (AiMessage m : history) {
                contents.add(Content.builder()
                        .role(m.getRole() != null ? m.getRole().toLowerCase() : "user")
                        .parts(List.of(Part.builder().text(m.getContent() != null ? m.getContent() : "").build()))
                        .build());
            }
        }
        List<Part> parts = new ArrayList<>();
        if (message != null && !message.isBlank()) {
            parts.add(Part.builder().text(message).build());
        }
        if (base64Image != null && !base64Image.isBlank()) {
            parts.add(Part.builder()
                .inlineData(Blob.builder()
                    .data(Base64.getDecoder().decode(parseBase64Data(base64Image)))
                    .mimeType(parseMimeType(base64Image))
                    .build())
                .build());
        }
        if (!parts.isEmpty()) {
            contents.add(Content.builder().role("user").parts(parts).build());
        }
        return contents;
    }

    private String parseBase64Data(String data) {
        if (data.contains("base64,")) return data.split("base64,")[1];
        return data;
    }
    private String parseMimeType(String data) {
        if (data.contains("base64,") && data.contains(":")) {
            return data.substring(data.indexOf(":") + 1, data.indexOf(";"));
        }
        return "image/jpeg";
    }

    private List<Tool> buildTools() {
        return List.of(
            Tool.builder().functionDeclarations(List.of(
                FunctionDeclaration.builder().name("insertTransaction")
                    .description("Create a new transaction.")
                    .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                        .properties(Map.of(
                            "amount", Schema.builder().type(new Type(Type.Known.NUMBER)).build(),
                            "categoryName", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "note", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "type", Schema.builder().type(new Type(Type.Known.STRING)).description("INCOME or EXPENSE").build(),
                            "date", Schema.builder().type(new Type(Type.Known.STRING)).description("ISO date").build(),
                            "walletName", Schema.builder().type(new Type(Type.Known.STRING)).build()))
                        .required(List.of("amount", "categoryName")).build()).build(),
                FunctionDeclaration.builder().name("updateTransaction")
                    .description("Update transaction.")
                    .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                        .properties(Map.of(
                            "amount", Schema.builder().type(new Type(Type.Known.NUMBER)).build(),
                            "noteKeywords", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "categoryName", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "date", Schema.builder().type(new Type(Type.Known.STRING)).build()))
                        .required(List.of("amount")).build()).build(),
                FunctionDeclaration.builder().name("deleteTransaction")
                        .description("Delete transactions.")
                        .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                                .properties(Map.of(
                                        "noteKeywords", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                                        "date", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                                        "deleteAll", Schema.builder().type(new Type(Type.Known.BOOLEAN)).build()))
                                .build()).build(),
                FunctionDeclaration.builder().name("queryTransaction")
                    .description("Query analytics.")
                    .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                        .properties(Map.of(
                            "metric", Schema.builder().type(new Type(Type.Known.STRING)).description("TOTAL, TOP_CATEGORY, TREND, PERCENTAGE, WEEKLY_PATTERN, LIST").build(),
                            "type", Schema.builder().type(new Type(Type.Known.STRING)).description("EXPENSE or INCOME").build(),
                            "startDate", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "endDate", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "categoryName", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                            "minAmount", Schema.builder().type(new Type(Type.Known.NUMBER)).build(),
                            "maxAmount", Schema.builder().type(new Type(Type.Known.NUMBER)).build()))
                        .required(List.of("metric")).build()).build(),
                FunctionDeclaration.builder().name("createBudget")
                     .description("Set an expense budget/limit.")
                     .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                         .properties(Map.of(
                             "amount", Schema.builder().type(new Type(Type.Known.NUMBER)).build(),
                             "categoryName", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                             "date", Schema.builder().type(new Type(Type.Known.STRING)).description("ISO month date").build()))
                         .required(List.of("amount", "categoryName")).build()).build(),
                FunctionDeclaration.builder().name("createIncomeGoal")
                        .description("Set an income goal.")
                        .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                                .properties(Map.of(
                                        "amount", Schema.builder().type(new Type(Type.Known.NUMBER)).build(),
                                        "categoryName", Schema.builder().type(new Type(Type.Known.STRING)).build()))
                                .required(List.of("amount", "categoryName")).build()).build(),
                FunctionDeclaration.builder().name("createSchedule")
                     .description("Create scheduled transaction.")
                     .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                         .properties(Map.of(
                             "amount", Schema.builder().type(new Type(Type.Known.NUMBER)).build(),
                             "categoryName", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                             "repeatType", Schema.builder().type(new Type(Type.Known.STRING)).description("DAILY, WEEKLY, MONTHLY").build(),
                             "repeatConfig", Schema.builder().type(new Type(Type.Known.STRING)).description("Day of month for MONTHLY (e.g. '5'), Day of week for WEEKLY (e.g. 'MONDAY')").build(),
                             "walletName", Schema.builder().type(new Type(Type.Known.STRING)).build()))
                         .required(List.of("amount", "categoryName", "repeatType")).build()).build(),
                FunctionDeclaration.builder().name("disableSchedule").description("Pause a schedule.").build(),
                FunctionDeclaration.builder().name("enableSchedule").description("Resume a schedule.").build(),
                FunctionDeclaration.builder().name("deleteSchedule").description("Delete a schedule.").build(),
                FunctionDeclaration.builder().name("listSchedules").description("List schedules.").build(),
                FunctionDeclaration.builder().name("explainTransactionSource").description("Explain transaction source.").build(),
                FunctionDeclaration.builder().name("getMonthlySummary").description("Monthly report.").build(),
                FunctionDeclaration.builder().name("getFinancialScore").description("Health score.").build(),
                FunctionDeclaration.builder().name("getFinancialAdvice").description("Savings/Affordability advice.")
                        .parameters(Schema.builder().type(new Type(Type.Known.OBJECT))
                                .properties(Map.of("adviceReply", Schema.builder().type(new Type(Type.Known.STRING)).build()))
                                .build()).build()
            )).build()
        );
    }

    @Getter @Setter @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParseResult {
        public String intent;
        public Boolean isConfirmation = false;
        public String adviceReply;
        public GeminiParsedQuery query;
        public GeminiParsedTarget target;
        public List<GeminiParsedEntry> entries;
        public boolean isRateLimited;
    }
    @Getter @Setter @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedTarget {
        public BigDecimal amount;
        public String categoryName;
        public String date;
        public String noteKeywords;
        public Boolean deleteAll;
        public String repeatType;
        public String repeatConfig;
    }
    @Getter @Setter @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedQuery {
        public String metric;
        public String type;
        public String startDate;
        public String endDate;
        public Integer limit;
        public String categoryName;
        public BigDecimal minAmount;
        public BigDecimal maxAmount;
    }
    @Getter @Setter @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeminiParsedEntry {
        public BigDecimal amount;
        public String categoryName;
        public String note;
        public String type;
        public String date;
        public String repeatType;
        public String repeatConfig;
        public String walletName;
    }
}
