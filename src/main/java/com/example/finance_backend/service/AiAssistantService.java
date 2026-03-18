package com.example.finance_backend.service;

import com.example.finance_backend.dto.AiAssistantRequest;
import com.example.finance_backend.dto.AiAssistantResponse;
import com.example.finance_backend.dto.CreateEntryRequest;
import com.example.finance_backend.dto.FinancialEntryDto;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Locale VI_LOCALE = new Locale("vi", "VN");
    private static final Locale EN_LOCALE = Locale.US;
    private static final String LANG_VI = "vi";
    private static final String LANG_EN = "en";
    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private final FinancialEntryService entryService;
    private final FinancialEntryRepository entryRepository;
    private final AccountRepository accountRepository;
    private final AiMessageRepository aiMessageRepository;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    @Value("${ai.gemini.model:gemini-flash-latest}")
    private String model;

    private Client client;

    private static String resolveLanguage(String requested, String message) {
        if (requested != null && !requested.isBlank()) {
            String normalized = requested.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith(LANG_EN)) {
                return LANG_EN;
            }
            if (normalized.startsWith(LANG_VI)) {
                return LANG_VI;
            }
        }
        if (containsVietnameseDiacritics(message)) {
            return LANG_VI;
        }
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (containsAny(lower, List.of(
                    "today", "yesterday", "this month", "last month", "this year", "last year",
                    "delete", "remove", "update", "change", "income", "expense", "spend", "spent", "buy", "purchase"))) {
                return LANG_EN;
            }
        }
        return LANG_VI;
    }

    private static boolean isEnglish(String language) {
        return LANG_EN.equals(language);
    }

    private static String t(String language, String vi, String en) {
        return isEnglish(language) ? en : vi;
    }

    private static boolean containsVietnameseDiacritics(String text) {
        if (text == null || text.isBlank()) return false;
        return !normalizeText(text).equals(text);
    }

    public AiAssistantResponse handle(AiAssistantRequest request) {
        final String message = request.getMessage() == null ? "" : request.getMessage().trim();
        final String language = resolveLanguage(request.getLanguage(), message);
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        if (message.isEmpty() && (request.getBase64Image() == null || request.getBase64Image().isBlank())) {
            AiAssistantResponse response = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(t(language,
                            "Bạn hãy nhập câu hỏi hoặc nội dung chi tiêu để mình xử lý nhé.",
                            "Please enter a question or a transaction so I can help."))
                    .build();
            response.setConversationId(conversationId);
            return response;
        }

        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        AiParseResult parsed = null;
        Exception geminiError = null;
        try {
            parsed = parseWithGemini(message, request.getBase64Image(), history, language);
        } catch (Exception e) {
            geminiError = e;
            log.warn("Gemini parse failed for message: {}", message, e);
        }

        if (parsed == null || parsed.intent == null || parsed.intent.isBlank()
                || "UNKNOWN".equalsIgnoreCase(parsed.intent)) {
            AiParseResult fallback = parseFallback(message, history);
            if (fallback != null && fallback.intent != null && !fallback.intent.isBlank()) {
                parsed = fallback;
            } else if (geminiError != null) {
                AiAssistantResponse response = AiAssistantResponse.builder()
                        .intent("UNKNOWN")
                        .reply(t(language,
                                "Mình chưa kết nối được AI. Hãy kiểm tra GEMINI/GOOGLE API key ở backend và thử lại.",
                                "I can't reach the AI right now. Please check the GEMINI/GOOGLE API key on the backend and try again."))
                        .build();
                return finalizeResponse(response, conversationId, message, request.getBase64Image());
            }
        }
        
        // ... (intent logic continues) ...

        if (parsed == null || parsed.intent == null) {
            AiAssistantResponse response = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(t(language,
                            "Mình chưa hiểu rõ ý bạn. Hãy thử: \"Tháng này tôi tiêu nhiều nhất vào cái gì?\" hoặc \"Hôm nay tôi ăn phở 45k\".",
                            "I didn't quite understand. Try: \"What did I spend the most on this month?\" or \"I had pho for 45k today\"."))
                    .build();
            return finalizeResponse(response, conversationId, message, request.getBase64Image());
        }

        final String intent = parsed.intent.trim().toUpperCase(Locale.ROOT);
        if ("INSERT".equals(intent)) {
            AiAssistantResponse response = handleInsert(message, parsed, request.getAccountId(), request.getUserId(), language);
            return finalizeResponse(response, conversationId, message, request.getBase64Image());
        }
        if ("QUERY".equals(intent)) {
            AiAssistantResponse response = handleQuery(parsed, language);
            return finalizeResponse(response, conversationId, message, request.getBase64Image());
        }
        if ("UPDATE".equals(intent)) {
            AiAssistantResponse response = handleUpdate(parsed, message, request.getAccountId(), language);
            return finalizeResponse(response, conversationId, message, request.getBase64Image());
        }
        if ("DELETE".equals(intent)) {
            AiAssistantResponse response = handleDelete(parsed, message, language);
            return finalizeResponse(response, conversationId, message, request.getBase64Image());
        }
        if ("ADVICE".equals(intent)) {
            AiAssistantResponse response = AiAssistantResponse.builder()
                    .intent("ADVICE")
                    .reply(parsed.adviceReply)
                    .build();
            return finalizeResponse(response, conversationId, message, request.getBase64Image());
        }

        AiAssistantResponse response = AiAssistantResponse.builder()
                .intent("UNKNOWN")
                .reply(t(language,
                        "Mình chưa hiểu rõ ý bạn. Hãy thử: \"Tháng trước tôi tiêu bao nhiêu\", \"Hôm nay tôi ăn phở 45k\" hoặc \"Xóa giao dịch 45k vừa rồi\".",
                        "I didn't quite understand. Try: \"How much did I spend last month\", \"I had pho for 45k today\", or \"Delete the 45k transaction\"."))
                .build();
        return finalizeResponse(response, conversationId, message, request.getBase64Image());
    }

    private AiParseResult parseWithGemini(String message, String base64Image, List<AiMessage> history, String language) throws JsonProcessingException {
        String prompt = buildPrompt(message, history, language);
        GenerateContentResponse response;
        
        if (base64Image != null && !base64Image.isBlank()) {
            // Multimodal request
            String data = base64Image;
            String mimeType = "image/jpeg"; // Default
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
            
            response = getClient().models.generateContent(model, List.of(content), null);
        } else {
            response = getClient().models.generateContent(model, prompt, null);
        }
        
        String text = response.text();
        if (text == null) {
            return null;
        }
        String json = extractJson(text);
        ObjectMapper mapper = objectMapper.copy();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(json, AiParseResult.class);
    }

    private AiAssistantResponse handleInsert(String originalMessage, AiParseResult parsed, Long forcedAccountId, Long userId, String language) {
        List<AiParsedEntry> entries = parsed.entries == null ? List.of() : parsed.entries;
        if (entries.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .refreshRequired(false)
                    .reply(t(language,
                            "Mình chưa thấy khoản chi/thu nào rõ ràng. Bạn thử ghi: \"Hôm nay ăn phở 45k\" nhé.",
                            "I couldn't detect a clear transaction. Try: \"I had pho for 45k today\"."))
                    .build();
        }
        AccountResolution accountResolution = resolveAccount(originalMessage, forcedAccountId, userId, language);
        if (accountResolution.errorMessage != null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply(accountResolution.errorMessage)
                    .build();
        }
        if (accountResolution.needsSelection) {
            return AiAssistantResponse.builder()
                    .intent("NEED_ACCOUNT")
                    .needsAccountSelection(true)
                    .reply(t(language,
                            "Bạn muốn dùng ví nào cho giao dịch này?",
                            "Which wallet should I use for this transaction?"))
                    .build();
        }
        if (accountResolution.accountId == null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply(t(language,
                            "Bạn chưa có tài khoản/ví. Hãy tạo tài khoản trước khi thêm giao dịch nhé.",
                            "You don't have any wallet/account yet. Please create one before adding a transaction."))
                    .build();
        }
        Long accountId = accountResolution.accountId;
        // Đảm bảo entry gắn đúng user: nếu request không có userId thì lấy từ chủ ví đã chọn
        Long effectiveUserId = userId;
        if (effectiveUserId == null && accountId != null) {
            effectiveUserId = accountRepository.findById(accountId)
                    .map(Account::getUserId)
                    .orElse(null);
        }

        Map<String, Long> nameToId = getNameToIdMap();
        Long fallbackCategoryId = resolveFallbackCategoryId(nameToId);
        if (fallbackCategoryId == null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply(t(language,
                            "Chưa có danh mục chi tiêu nào. Hãy tạo danh mục trước nhé.",
                            "No categories found. Please create a category first."))
                    .build();
        }
        Long incomeCategoryId = resolveCategoryId(nameToId, "Nạp tiền", fallbackCategoryId);

        List<String> savedLines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int createdCount = 0;
        for (AiParsedEntry entry : entries) {
            if (entry == null || entry.amount == null || entry.amount.compareTo(BigDecimal.ZERO) <= 0)
                continue;
            String type = entry.type == null || entry.type.isBlank() ? "EXPENSE" : entry.type.toUpperCase(Locale.ROOT);
            if (!"EXPENSE".equals(type) && !"INCOME".equals(type))
                type = "EXPENSE";
            String resolvedCategoryName = normalizeCategoryName(entry.categoryName);
            if ("INCOME".equals(type)) {
                resolvedCategoryName = "Nạp tiền";
            } else if (resolvedCategoryName == null || resolvedCategoryName.isBlank()) {
                resolvedCategoryName = null;
            }
            Long preferredFallback = "INCOME".equals(type) ? incomeCategoryId : fallbackCategoryId;
            Long categoryId = resolveCategoryId(nameToId, resolvedCategoryName, preferredFallback);
            LocalDate date = parseDate(entry.date, LocalDate.now());
            String note = entry.note != null && !entry.note.isBlank() ? entry.note.trim() : originalMessage;
            if (note.length() > 2000)
                note = note.substring(0, 1997) + "...";

            CreateEntryRequest req = CreateEntryRequest.builder()
                    .amount(entry.amount)
                    .note(note)
                    .categoryId(categoryId)
                    .accountId(accountId)
                    .type(type)
                    .transactionDate(date)
                    .source("AI")
                    .build();
            try {
                FinancialEntryDto created = entryService.create(req, effectiveUserId);
                createdCount++;
                String catName = created.getCategoryName() != null ? created.getCategoryName() : "";
                String sign = "INCOME".equals(type) ? "+" : "-";
                savedLines.add(String.format("%s %s • %s", sign, formatVnd(entry.amount, language), catName));
            } catch (Exception ex) {
                log.error("Failed to create entry from AI parse: {}", req, ex);
                errors.add(ex.getMessage() != null ? ex.getMessage() : "Lỗi không xác định");
            }
        }

        if (createdCount == 0) {
            String errorText = errors.isEmpty()
                    ? t(language,
                        "Mình chưa thể tạo giao dịch. Hãy thử nhập rõ số tiền và nội dung nhé.",
                        "I couldn't create the transaction. Please include a clear amount and description.")
                    : t(language,
                        "Không thể lưu giao dịch: ",
                        "Couldn't save transaction: ") + errors.get(0);
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .refreshRequired(false)
                    .reply(errorText)
                    .build();
        }

        StringBuilder reply = new StringBuilder();
        reply.append(t(language, "Đã lưu ", "Saved "))
             .append(createdCount)
             .append(t(language, " giao dịch.", " transaction(s)."));
        if (!savedLines.isEmpty()) {
            reply.append("\n").append(String.join("\n", savedLines));
        }

        return AiAssistantResponse.builder()
                .intent("INSERT")
                .createdCount(createdCount)
                .refreshRequired(true)
                .reply(reply.toString())
                .build();
    }

    private AiAssistantResponse handleQuery(AiParseResult parsed, String language) {
        AiParsedQuery q = parsed.query != null ? parsed.query : new AiParsedQuery();

        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(q.startDate, today.withDayOfMonth(1));
        LocalDate end = parseDate(q.endDate, today);
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        List<com.example.finance_backend.entity.FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        String typeFilter = q.type == null ? "EXPENSE" : q.type.toUpperCase(Locale.ROOT);
        if (!List.of("EXPENSE", "INCOME", "TRANSFER", "ALL").contains(typeFilter)) {
            typeFilter = "EXPENSE";
        }
        if (!"ALL".equals(typeFilter)) {
            final EntryType finalType = EntryType.valueOf(typeFilter);
            entries = entries.stream()
                    .filter(e -> e.getType() == finalType)
                    .collect(Collectors.toList());
        }

        if (q.categoryName != null && !q.categoryName.isBlank()) {
            String normalizedCategory = normalizeCategoryName(q.categoryName);
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, normalizedCategory, null);
            if (catId != null) {
                Long finalCatId = catId;
                entries = entries.stream()
                        .filter(e -> Objects.equals(e.getCategoryId(), finalCatId))
                        .collect(Collectors.toList());
            }
        }

        if (entries.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("QUERY")
                    .reply(t(language,
                            "Không có giao dịch nào trong khoảng thời gian này.",
                            "No transactions found in this period."))
                    .build();
        }

        String metric = q.metric == null ? "TOTAL" : q.metric.toUpperCase(Locale.ROOT);
        switch (metric) {
            case "TOP_CATEGORY":
                return buildTopCategoryReply(start, end, entries, language);
            case "LIST":
                return buildListReply(start, end, entries, q.limit, language);
            case "AVERAGE":
                return buildAverageReply(start, end, entries, typeFilter, language);
            case "TREND":
                return buildTrendReply(start, end, typeFilter, language);
            case "PERCENTAGE":
                return buildPercentageReply(start, end, entries, language);
            case "TOTAL":
            default:
                return buildTotalReply(start, end, entries, typeFilter, language);
        }
    }

    private AiAssistantResponse buildAverageReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            String typeFilter,
            String language) {
        BigDecimal total = entries.stream()
                .map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);
        BigDecimal average = total.divide(new BigDecimal(days), 0, RoundingMode.HALF_UP);
        String reply;
        if ("ALL".equals(typeFilter)) {
            reply = isEnglish(language)
                    ? String.format("From %s to %s (%d days), your average daily total is %s.",
                            start.format(DATE_FMT), end.format(DATE_FMT), days, formatVnd(average, language))
                    : String.format("Trong khoảng từ %s đến %s (%d ngày), trung bình mỗi ngày tổng giao dịch là %s.",
                            start.format(DATE_FMT), end.format(DATE_FMT), days, formatVnd(average, language));
        } else {
            String verb = "INCOME".equals(typeFilter)
                    ? t(language, "thu", "income")
                    : t(language, "chi", "spending");
            reply = isEnglish(language)
                    ? String.format("From %s to %s (%d days), your average daily %s is %s.",
                            start.format(DATE_FMT), end.format(DATE_FMT), days, verb, formatVnd(average, language))
                    : String.format("Trong khoảng từ %s đến %s (%d ngày), trung bình mỗi ngày bạn %s %s.",
                            start.format(DATE_FMT), end.format(DATE_FMT), days, verb, formatVnd(average, language));
        }
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildTrendReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate prevStart = start.minusDays(days);
        LocalDate prevEnd = start.minusDays(1);

        List<com.example.finance_backend.entity.FinancialEntry> currentEntries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);
        List<com.example.finance_backend.entity.FinancialEntry> prevEntries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(prevStart, prevEnd);

        if (!"ALL".equals(typeFilter)) {
            final EntryType finalType = EntryType.valueOf(typeFilter);
            currentEntries = currentEntries.stream().filter(e -> e.getType() == finalType).collect(Collectors.toList());
            prevEntries = prevEntries.stream().filter(e -> e.getType() == finalType).collect(Collectors.toList());
        }

        BigDecimal currentTotal = currentEntries.stream().map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevTotal = prevEntries.stream().map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String label;
        if ("ALL".equals(typeFilter)) {
            label = t(language, "giao dịch", "transactions");
        } else {
            label = "INCOME".equals(typeFilter) ? t(language, "thu", "income") : t(language, "chi", "expense");
        }
        String trend = t(language, "không đổi", "no change");
        if (prevTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = currentTotal.subtract(prevTotal);
            BigDecimal percent = diff.multiply(new BigDecimal("100")).divide(prevTotal, 1, RoundingMode.HALF_UP);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                trend = t(language, "tăng " + percent + "%", "up " + percent + "%");
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                trend = t(language, "giảm " + percent.abs() + "%", "down " + percent.abs() + "%");
            }
        } else if (currentTotal.compareTo(BigDecimal.ZERO) > 0) {
            trend = t(language, "mới phát sinh", "new activity");
        }

        String reply = isEnglish(language)
                ? String.format("Compared to the previous period (%s to %s), your total %s is %s. (Current: %s, Previous: %s)",
                        prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend,
                        formatVnd(currentTotal, language), formatVnd(prevTotal, language))
                : String.format("So với giai đoạn trước (%s đến %s), tổng %s của bạn %s. (Hiện tại: %s, Trước đó: %s)",
                        prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend,
                        formatVnd(currentTotal, language), formatVnd(prevTotal, language));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildPercentageReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            String language) {
        BigDecimal total = entries.stream()
                .map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return AiAssistantResponse.builder()
                    .intent("QUERY")
                    .reply(t(language, "Không có dữ liệu để tính tỷ lệ.", "No data available to calculate percentages."))
                    .build();
        }

        Map<Long, BigDecimal> catTotals = new HashMap<>();
        for (var e : entries) {
            catTotals.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        StringBuilder sb = new StringBuilder();
        sb.append(isEnglish(language)
                ? String.format("Spending breakdown from %s to %s:\n", start.format(DATE_FMT), end.format(DATE_FMT))
                : String.format("Tỷ lệ chi tiêu từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));

        catTotals.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .forEach(entry -> {
                    BigDecimal pct = entry.getValue().multiply(new BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP);
                    sb.append(String.format("- %s: %s%% (%s)\n",
                            idToName.getOrDefault(entry.getKey(), "Khác"),
                            pct,
                            formatVnd(entry.getValue(), language)));
                });

        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(sb.toString().trim())
                .build();
    }

    private AiAssistantResponse handleUpdate(AiParseResult parsed, String originalMessage, Long forcedAccountId, String language) {
        List<com.example.finance_backend.entity.FinancialEntry> targets = findTargetEntries(parsed.target);
        if (targets.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("UPDATE")
                    .reply(t(language,
                            "Mình không tìm thấy giao dịch nào khớp để sửa.",
                            "I couldn't find a matching transaction to update."))
                    .build();
        }
        if (targets.size() > 1) {
            return AiAssistantResponse.builder()
                    .intent("UPDATE")
                    .reply(t(language,
                            "Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: ngày hoặc số tiền cụ thể).",
                            "Multiple transactions match. Please be more specific (e.g., date or amount)."))
                    .build();
        }

        com.example.finance_backend.entity.FinancialEntry entry = targets.get(0);
        AiParsedEntry newData = (parsed.entries != null && !parsed.entries.isEmpty()) ? parsed.entries.get(0) : null;

        if (newData == null) {
            // Thử cứu vãn (salvage) dữ liệu nếu Gemini hụt nhưng message có pattern "thành"
            String[] parts = originalMessage.toLowerCase(VI_LOCALE)
                    .split("\\b(thanh|thành|sang|thay|den|đến|to|into|as)\\b");
            if (parts.length >= 2) {
                newData = new AiParsedEntry();
                newData.amount = extractAmount(parts[1]);
                
                // Chỉ lấy note mới nếu nó chứa thông tin khác ngoài số tiền
                String notePart = parts[1].replaceAll("[0-9.,dđkK]+", " ").trim();
                if (notePart.length() > 2) { // Ngưỡng tối thiểu để coi là có note mới
                    newData.note = parts[1].trim();
                }
                
                String catMatch = detectCategory(normalizeText(parts[1]));
                if (catMatch != null) {
                    newData.categoryName = catMatch;
                }
            }
        }

        if (newData == null) {
            return AiAssistantResponse.builder()
                    .intent("UPDATE")
                    .reply(t(language,
                            "Bạn muốn sửa thông tin gì của giao dịch này?",
                            "What would you like to update for this transaction?"))
                    .build();
        }

        if (newData.amount != null) entry.setAmount(newData.amount);
        if (newData.note != null && !newData.note.isBlank()) entry.setNote(newData.note);
        if (newData.date != null && !newData.date.isBlank()) {
             LocalDate d = parseDate(newData.date, null);
             if (d != null) entry.setTransactionDate(d);
        }
        if (newData.categoryName != null && !newData.categoryName.isBlank()) {
            newData.categoryName = normalizeCategoryName(newData.categoryName);
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, newData.categoryName, entry.getCategoryId());
            entry.setCategoryId(catId);
        }

        entryRepository.save(entry);
        return AiAssistantResponse.builder()
                .intent("UPDATE")
                .refreshRequired(true)
                .reply(t(language,
                        "Đã cập nhật giao dịch: " + formatVnd(entry.getAmount(), language) + " - " + entry.getNote(),
                        "Updated transaction: " + formatVnd(entry.getAmount(), language) + " - " + entry.getNote()))
                .build();
    }

    private AiAssistantResponse handleDelete(AiParseResult parsed, String originalMessage, String language) {
        List<com.example.finance_backend.entity.FinancialEntry> targets = findTargetEntries(parsed.target);
        if (targets.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("DELETE")
                    .reply(t(language,
                            "Mình không tìm thấy giao dịch nào khớp để xóa.",
                            "I couldn't find a matching transaction to delete."))
                    .build();
        }

        boolean deleteAll = parsed.target != null && Boolean.TRUE.equals(parsed.target.deleteAll);
        if (!deleteAll && targets.size() > 1) {
            return AiAssistantResponse.builder()
                    .intent("DELETE")
                    .reply(t(language,
                            "Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: 'Xóa tất cả' hoặc số tiền cụ thể).",
                            "Multiple transactions match. Please be more specific (e.g., 'delete all' or a specific amount)."))
                    .build();
        }

        int deletedCount = targets.size();
        entryRepository.deleteAll(targets);

        String reply = deletedCount == 1
            ? t(language,
                "Đã xóa giao dịch: " + formatVnd(targets.get(0).getAmount(), language) + " - " + targets.get(0).getNote(),
                "Deleted transaction: " + formatVnd(targets.get(0).getAmount(), language) + " - " + targets.get(0).getNote())
            : t(language,
                "Đã xóa " + deletedCount + " giao dịch khớp với yêu cầu.",
                "Deleted " + deletedCount + " matching transactions.");

        return AiAssistantResponse.builder()
                .intent("DELETE")
                .refreshRequired(true)
                .reply(reply)
                .build();
    }


    private List<com.example.finance_backend.entity.FinancialEntry> findTargetEntries(AiParsedTarget target) {
        if (target == null) return List.of();
        String normalizedTargetCategory = target.categoryName != null ? normalizeCategoryName(target.categoryName) : null;
        LocalDate searchDate = parseDate(target.date, null);
        
        // Nếu không có ngày cụ thể, mở rộng phạm vi ra 30 ngày để dễ tìm hơn
        LocalDate start = searchDate != null ? searchDate : LocalDate.now().minusDays(30);
        LocalDate end = searchDate != null ? searchDate : LocalDate.now();

        List<com.example.finance_backend.entity.FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream()
                .filter(e -> {
                    // 1. Kiểm tra số tiền
                    if (target.amount != null && e.getAmount().compareTo(target.amount) != 0) return false;
                    
                    // 2. Kiểm tra danh mục (Rất quan trọng khi note trống)
                    if (normalizedTargetCategory != null && !normalizedTargetCategory.isBlank()) {
                        String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "").toLowerCase(VI_LOCALE);
                        String normalizedCatName = normalizeText(catName);
                        String normalizedTargetCat = normalizeText(normalizedTargetCategory.toLowerCase(VI_LOCALE));
                        if (!normalizedCatName.contains(normalizedTargetCat) && !normalizedTargetCat.contains(normalizedCatName)) return false;
                    }

                    // 3. Kiểm tra từ khóa trong ghi chú
                    if (target.noteKeywords != null && !target.noteKeywords.isBlank()) {
                        String note = e.getNote() != null ? normalizeText(e.getNote().toLowerCase(VI_LOCALE)) : "";
                        String keywords = normalizeText(target.noteKeywords.toLowerCase(VI_LOCALE));
                        
                        // Loại bỏ STOP WORDS và NOISE WORDS (từ chỉ hành động, ngày tháng, đơn vị tiền) giúp tìm kiếm chính xác hơn
                        keywords = keywords.replaceAll("\\b(sua|sửa|doi|đổi|cap nhat|cập nhật|thanh|thành|sang|thay|den|đến|xoa|huy|bo|giup|giao dich|khoan|cai|nay|tat ca|het|hom nay|hom qua|ngay|thang|nam|vi|momo|tien|chi|tieu|vua|nay|chieu|sang|toi|update|change|edit|set|delete|remove|transaction|entry|this|that|all|everything|today|yesterday|day|month|year|wallet|money|expense|income|spend|spent|buy|payment|pay|recent|nap|nap tien|gui|rut|banking|transfer)\\b", "")
                                        .replaceAll("[0-9.,dđkK]+", " ")
                                        .replaceAll("\\s+", " ")
                                        .trim();
                                        
                        // Nếu sau khi lọc keywords vẫn còn nội dung, thì mới so khớp với note
                        if (!keywords.isBlank() && !note.contains(keywords)) return false;
                    }
                    
                    return true;
                })
                .collect(Collectors.toList());
    }

    private AiAssistantResponse buildTotalReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            String typeFilter,
            String language) {
        BigDecimal total = entries.stream()
                .map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String label;
        if ("ALL".equals(typeFilter)) {
            label = t(language, "Tổng giao dịch", "Total transactions");
        } else {
            label = "INCOME".equals(typeFilter)
                    ? t(language, "Tổng thu", "Total income")
                    : t(language, "Tổng chi", "Total expense");
        }
        String reply = isEnglish(language)
                ? String.format("%s from %s to %s is %s.",
                    label,
                    start.format(DATE_FMT),
                    end.format(DATE_FMT),
                    formatVnd(total, language))
                : String.format("%s từ %s đến %s là %s.",
                    label,
                    start.format(DATE_FMT),
                    end.format(DATE_FMT),
                    formatVnd(total, language));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildTopCategoryReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            String language) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (var e : entries) {
            totals.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }
        var max = totals.entrySet().stream()
                .max(Comparator.comparing(e -> e.getValue()));
        if (max.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("QUERY")
                    .reply(t(language,
                            "Chưa có dữ liệu để tính nhóm chi tiêu lớn nhất.",
                            "No data available to determine the top spending category."))
                    .build();
        }
        Long catId = max.get().getKey();
        String catName = categoryService.getIdToNameMap().getOrDefault(catId, "Khác");
        String reply = isEnglish(language)
                ? String.format("From %s to %s, you spent the most on %s: %s.",
                        start.format(DATE_FMT),
                        end.format(DATE_FMT),
                        catName,
                        formatVnd(max.get().getValue(), language))
                : String.format("Trong khoảng %s đến %s, bạn chi nhiều nhất vào %s: %s.",
                        start.format(DATE_FMT),
                        end.format(DATE_FMT),
                        catName,
                        formatVnd(max.get().getValue(), language));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildListReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            Integer limit,
            String language) {
        int max = (limit == null || limit <= 0) ? 10 : Math.min(limit, 20);
        List<com.example.finance_backend.entity.FinancialEntry> slice = entries.stream()
                .limit(max)
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append(isEnglish(language)
                ? String.format("Transactions from %s to %s:\n", start.format(DATE_FMT), end.format(DATE_FMT))
                : String.format("Danh sách giao dịch từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));
        for (var e : slice) {
            String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "Khác");
            String note = e.getNote() != null ? e.getNote() : "";
            sb.append(String.format("- %s • %s", formatVnd(e.getAmount(), language), catName));
            if (!note.isBlank()) {
                sb.append(" (").append(trimNote(note)).append(")");
            }
            sb.append("\n");
        }
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(sb.toString().trim())
                .build();
    }

    private String buildPrompt(String message, List<AiMessage> history, String language) {
        String safeMessage = message == null ? "" : message.trim();
        LocalDate today = LocalDate.now();
        List<String> categories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.toList());
        String categoriesStr = String.join(", ", categories);
        String historyBlock = formatHistory(history, language);

        if (isEnglish(language)) {
            return """
                    You are an AI assistant for a personal finance app. Analyze the user's intent and extract data.
                    Today is %s (Asia/Ho_Chi_Minh).
                    Valid categories: %s.
                    Conversation history (chronological, role USER/ASSISTANT):
                    %s

                    OUTPUT REQUIREMENTS:
                    - Return raw JSON only, no markdown or extra text.
                    - Always use YYYY-MM-DD date format.
                    - Determine intent: INSERT (add), QUERY (query), UPDATE (edit), DELETE (delete), ADVICE (general advice/Q&A).
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
                      "intent": "QUERY" | "INSERT" | "UPDATE" | "DELETE" | "ADVICE" | "UNKNOWN",
                      "adviceReply": "string (only for ADVICE)",
                      "query": {
                        "metric": "TOTAL" | "TOP_CATEGORY" | "LIST" | "AVERAGE" | "TREND" | "PERCENTAGE",
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

                    UPDATE/DELETE EXAMPLES:
                    - "Change yesterday's lunch to 50k": intent=UPDATE, target={amount: null, noteKeywords: "lunch", date: "yesterday"}, entries=[{amount: 50000}]
                    - "Delete the 45k transaction": intent=DELETE, target={amount: 45000}
                    - "Delete all transactions today": intent=DELETE, target={date: "today", deleteAll: true}
                    - "Delete the 50k gas transaction": intent=DELETE, target={amount: 50000, noteKeywords: "gas"} (DO NOT choose INSERT/QUERY if the message contains "delete/remove")

                    ADVANCED QUERY EXAMPLES:
                    - "Average daily spending this month": metric=AVERAGE
                    - "Did my spending increase compared to last month": metric=TREND
                    - "Spending by category percentage": metric=PERCENTAGE

                    ADVICE EXAMPLE:
                    - "How can I save money?": intent=ADVICE, adviceReply="To save money, you should..."

                    INPUT:
                    %s
                    """.formatted(today.format(DATE_FMT), categoriesStr, historyBlock, safeMessage);
        }

        return """
                Bạn là trợ lý AI cho ứng dụng quản lý chi tiêu. Hãy phân tích ý định và trích xuất dữ liệu.
                Hôm nay là %s (Asia/Ho_Chi_Minh).
                Danh sách danh mục hợp lệ: %s.
                Lịch sử hội thoại (theo thời gian, vai trò USER/ASSISTANT):
                %s

                YÊU CẦU ĐẦU RA:
                - Chỉ trả về JSON thuần, không markdown, không giải thích thêm.
                - Luôn dùng định dạng ngày YYYY-MM-DD.
                - Xác định intent: INSERT (thêm), QUERY (tra cứu), UPDATE (sửa), DELETE (xóa), ADVICE (tư vấn/hỏi đáp chung).
                - Nếu là ADVICE, hãy điền câu trả lời vào trường "adviceReply".
                - Khi UPDATE/DELETE, dùng "target" để xác định giao dịch cần tác động (số tiền, ngày, danh mục cũ). Dùng "entries[0]" để chứa thông tin mới (nếu là UPDATE).
                - Nếu muốn xóa tất cả giao dịch trong một khoảng thời gian/điều kiện, đặt "target.deleteAll = true".
                - Nếu người dùng gửi ảnh hóa đơn/biên lai, hãy thực hiện OCR và tóm tắt lại các thông tin tìm thấy (ngày, các món đồ, tổng tiền). Hỏi người dùng xem họ có muốn thêm các giao dịch này không. Đặt intent thành ADVICE và để nội dung tóm tắt vào "adviceReply".
                - Nếu người dùng yêu cầu SỬA hoặc THAY ĐỔI thông tin của hóa đơn vừa gửi (khi chưa lưu), KHÔNG được dùng intent UPDATE. Hãy cập nhật lại bản tóm tắt của bạn và hỏi lại xem đã đúng chưa. Tiếp tục giữ intent là ADVICE.
                - CHỈ dùng intent UPDATE khi người dùng muốn sửa một giao dịch ĐÃ ĐƯỢC LƯU vào cơ sở dữ liệu từ trước.
                - KHÔNG đặt intent thành INSERT trực tiếp từ ảnh trừ khi người dùng nói "Thêm cái này" hoặc "Lưu lại".

                SCHEMA:
                {
                  "intent": "QUERY" | "INSERT" | "UPDATE" | "DELETE" | "ADVICE" | "UNKNOWN",
                  "adviceReply": "string (chỉ dùng cho ADVICE)",
                  "query": {
                    "metric": "TOTAL" | "TOP_CATEGORY" | "LIST" | "AVERAGE" | "TREND" | "PERCENTAGE",
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

                VÍ DỤ UPDATE/DELETE:
                - "Sửa khoản ăn trưa hôm qua thành 50k": intent=UPDATE, target={amount: null, noteKeywords: "ăn trưa", date: "hôm qua"}, entries=[{amount: 50000}]
                - "Xóa giao dịch 45k": intent=DELETE, target={amount: 45000}
                - "Xóa tất cả giao dịch hôm nay": intent=DELETE, target={date: "hôm nay", deleteAll: true}
                - "Xóa giao dịch đổ xăng 50k": intent=DELETE, target={amount: 50000, noteKeywords: "đổ xăng"} (TUYỆT ĐỐI KHÔNG CHỌN INSERT/QUERY CHO CÂU CÓ TỪ 'XÓA')

                VÍ DỤ QUERY NÂNG CAO:
                - "Trung bình mỗi ngày tiêu bao nhiêu": metric=AVERAGE
                - "Chi phí tháng này tăng hay giảm so với tháng trước": metric=TREND
                - "Tỷ lệ chi tiêu các nhóm": metric=PERCENTAGE

                VÍ DỤ ADVICE:
                - "Làm sao để tiết kiệm tiền?": intent=ADVICE, adviceReply="Để tiết kiệm tiền, bạn nên..."

                ĐẦU VÀO:
                %s
                """.formatted(today.format(DATE_FMT), categoriesStr, historyBlock, safeMessage);
    }

    private Client getClient() {
        if (client != null)
            return client;
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

    private Map<String, Long> getNameToIdMap() {
        Map<Long, String> idToName = categoryService.getIdToNameMap();
        Map<String, Long> map = new HashMap<>();
        for (var entry : idToName.entrySet()) {
            if (entry.getValue() != null) {
                map.put(entry.getValue().toLowerCase(Locale.ROOT), entry.getKey());
            }
        }
        return map;
    }

    private Long resolveCategoryId(Map<String, Long> nameToId, String name, Long fallback) {
        if (name == null || name.isBlank())
            return fallback;
        Long id = nameToId.get(name.trim().toLowerCase(Locale.ROOT));
        if (id != null)
            return id;
        String normalizedTarget = normalizeText(name.trim().toLowerCase(Locale.ROOT));
        for (var entry : nameToId.entrySet()) {
            if (normalizeText(entry.getKey()).equals(normalizedTarget)) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    private Long resolveFallbackCategoryId(Map<String, Long> nameToId) {
        Long other = nameToId.get("khác");
        if (other != null)
            return other;
        return nameToId.values().stream().findFirst().orElse(null);
    }

    private static LocalDate parseDate(String date, LocalDate fallback) {
        if (date == null || date.isBlank())
            return fallback;
        try {
            return LocalDate.parse(date.trim(), DATE_FMT);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatVnd(BigDecimal value, String language) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
        Locale locale = isEnglish(language) ? EN_LOCALE : VI_LOCALE;
        String suffix = isEnglish(language) ? " VND" : " đ";
        return NumberFormat.getInstance(locale).format(normalized) + suffix;
    }

    private static String trimNote(String note) {
        if (note == null)
            return "";
        String trimmed = note.trim();
        if (trimmed.length() <= 80)
            return trimmed;
        return trimmed.substring(0, 77) + "...";
    }

    private AccountResolution resolveAccount(String message, Long forcedAccountId, Long userId, String language) {
        List<Account> accounts = (userId != null)
                ? accountRepository.findByUserIdOrderByNameAsc(userId)
                : accountRepository.findAll();
        if (accounts.isEmpty()) {
            return AccountResolution.none();
        }
        if (forcedAccountId != null) {
            boolean exists = accounts.stream().anyMatch(a -> Objects.equals(a.getId(), forcedAccountId));
            if (exists) {
                return AccountResolution.selected(forcedAccountId);
            }
            return AccountResolution.error(t(language,
                    "Ví/tài khoản không tồn tại.",
                    "Wallet/account does not exist."));
        }
        if (accounts.size() == 1) {
            return AccountResolution.selected(accounts.get(0).getId());
        }
        Long matched = matchAccountInMessage(message, accounts);
        if (matched != null) {
            return AccountResolution.selected(matched);
        }
        return AccountResolution.needsSelection();
    }

    private Long matchAccountInMessage(String message, List<Account> accounts) {
        if (message == null || message.isBlank()) return null;
        String normalizedMessage = normalizeText(message.toLowerCase(Locale.ROOT));
        Long bestMatchId = null;
        int bestLen = 0;
        for (Account a : accounts) {
            String name = a.getName() == null ? "" : a.getName();
            String normalizedName = normalizeText(name.toLowerCase(Locale.ROOT));
            if (normalizedName.isBlank()) continue;
            if (normalizedMessage.contains(normalizedName) && normalizedName.length() > bestLen) {
                bestLen = normalizedName.length();
                bestMatchId = a.getId();
            }
        }
        return bestMatchId;
    }

    private AiAssistantResponse finalizeResponse(AiAssistantResponse response, String conversationId, String message, String base64Image) {
        response.setConversationId(conversationId);
        boolean hasImage = base64Image != null && !base64Image.isBlank();
        if ((message != null && !message.isBlank()) || hasImage) {
            String safeContent = (message == null || message.isBlank()) ? "[Gửi ảnh hóa đơn]" : message.trim();
            saveMessage(conversationId, "USER", safeContent);
            if (response.getReply() != null) {
                saveMessage(conversationId, "ASSISTANT", response.getReply());
            }
        }
        return response;
    }

    private void saveMessage(String conversationId, String role, String content) {
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.length() > 4000) {
            safeContent = safeContent.substring(0, 3997) + "...";
        }
        aiMessageRepository.save(AiMessage.builder()
                .conversationId(conversationId)
                .role(role)
                .content(safeContent)
                .build());
    }

    private static String formatHistory(List<AiMessage> history, String language) {
        if (history == null || history.isEmpty())
            return t(language, "Không có.", "None.");
        StringBuilder sb = new StringBuilder();
        for (AiMessage m : history) {
            String role = m.getRole() != null ? m.getRole().toUpperCase(Locale.ROOT) : "USER";
            String content = m.getContent() != null ? m.getContent() : "";
            sb.append(role).append(": ").append(content).append("\n");
        }
        return sb.toString().trim();
    }

    private static boolean shouldUseHistory(String normalized, BigDecimal amount) {
        if (amount != null)
            return false;
        if (normalized.length() <= 12)
            return true;
        return containsAny(normalized, List.of(
                "con hom qua", "cai nao", "nhieu nhat", "luc nao", "con nua", "con khong",
                "hom qua", "hom nay",
                "yesterday", "today", "which one", "most", "any more", "anything else"));
    }

    private static String lastUserMessage(List<AiMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            AiMessage m = history.get(i);
            if (m.getRole() != null && m.getRole().equalsIgnoreCase("USER")) {
                return m.getContent();
            }
        }
        return null;
    }

    private static String normalizeCategoryName(String name) {
        if (name == null)
            return null;
        String trimmed = name.trim();
        String normalized = normalizeText(trimmed.toLowerCase(Locale.ROOT));
        if (normalized.equals("nap vi") || normalized.equals("nap tien") || normalized.equals("top up")
                || normalized.equals("topup") || normalized.equals("deposit") || normalized.equals("income")
                || normalized.equals("salary") || normalized.equals("wage") || normalized.equals("bonus")
                || normalized.equals("refund") || normalized.equals("cashback") || normalized.equals("transfer in")) {
            return "Nạp tiền";
        }
        if (normalized.equals("food") || normalized.equals("meal") || normalized.equals("lunch")
                || normalized.equals("dinner") || normalized.equals("breakfast") || normalized.equals("restaurant")
                || normalized.equals("coffee") || normalized.equals("tea") || normalized.equals("drink")
                || normalized.equals("milk tea") || normalized.equals("snack")) {
            return "Ăn uống";
        }
        if (normalized.equals("parking")) {
            return "Gửi xe";
        }
        if (normalized.equals("gas") || normalized.equals("gasoline") || normalized.equals("petrol")
                || normalized.equals("fuel") || normalized.equals("transport") || normalized.equals("transportation")
                || normalized.equals("taxi") || normalized.equals("uber") || normalized.equals("grab")) {
            return "Xăng xe";
        }
        if (normalized.equals("shopping") || normalized.equals("groceries") || normalized.equals("supermarket")
                || normalized.equals("mall") || normalized.equals("clothes") || normalized.equals("purchase") || normalized.equals("buy")) {
            return "Mua sắm";
        }
        if (normalized.equals("entertainment") || normalized.equals("movie") || normalized.equals("cinema")
                || normalized.equals("netflix") || normalized.equals("game") || normalized.equals("gift")
                || normalized.equals("present") || normalized.equals("birthday")) {
            return "Giải trí";
        }
        if (normalized.equals("health") || normalized.equals("medical") || normalized.equals("hospital")
                || normalized.equals("medicine") || normalized.equals("pharmacy")) {
            return "Y tế";
        }
        if (normalized.equals("education") || normalized.equals("school") || normalized.equals("tuition")
                || normalized.equals("book") || normalized.equals("course") || normalized.equals("study")) {
            return "Giáo dục";
        }
        if (normalized.equals("other") || normalized.equals("others")) {
            return "Khác";
        }
        return trimmed;
    }

    private AiParseResult parseFallback(String message, List<AiMessage> history) {
        String lower = message.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty())
            return null;

        String normalized = normalizeText(lower);
        boolean isDelete = containsAny(normalized, List.of(
                "xoa", "huy", "bo qua",
                "delete", "remove", "erase", "clear", "cancel"));
        boolean isUpdate = containsAny(normalized, List.of(
                "sua", "doi", "cap nhat", "thanh",
                "update", "change", "edit", "set", "replace"));
        boolean isAll = normalized.contains("tat ca") || normalized.contains("het")
                || normalized.contains("all") || normalized.contains("everything");

        if (isDelete && !normalized.contains("bao nhieu") && !normalized.contains("how much") && !normalized.contains("how many")) {
            AiParseResult result = new AiParseResult();
            result.intent = "DELETE";
            result.target = new AiParsedTarget();
            result.target.deleteAll = isAll;
            result.target.date = detectSingleDate(normalized, LocalDate.now()).format(DATE_FMT);
            BigDecimal amt = extractAmount(lower);
            if (amt != null) result.target.amount = amt;
            
            // Nếu là xóa tất cả, không cần trích xuất keyword từ nội dung
            if (!isAll) {
                // Thử trích xuất keyword: bỏ đi các từ chỉ lệnh và ngày tháng
                String kw = lower.replaceAll("\\b(xoa|huy|bo|giup|giao dich|khoan|cai|nay|hom nay|hom qua|ngay|thang|nam|vi|momo|delete|remove|transaction|entry|this|that|today|yesterday|day|month|year|wallet)\\b", "")
                                 .replaceAll("\\s+", " ")
                                 .trim();
                // Bỏ tiền ra khỏi keyword nếu có
                if (amt != null) {
                    kw = kw.replace(amt.toString(), "")
                           .replace(amt.divide(new BigDecimal("1000")).toString() + "k", "")
                           .replaceAll("\\s+", " ")
                           .trim();
                }
                result.target.noteKeywords = kw;
            }
            return result;
        }

        boolean isQuery = containsAny(normalized, List.of(
                "bao nhieu", "tong", "thong ke", "nhieu nhat", "cao nhat",
                "danh sach", "liet ke", "thang nay", "thang truoc", "hom nay", "hom qua", "nam nay",
                "how much", "total", "summary", "most", "highest", "list", "show",
                "this month", "last month", "today", "yesterday", "this year", "last year", "this week", "last week",
                "average", "trend", "percent", "percentage", "ratio"));

        BigDecimal amount = extractAmount(lower);
        if (amount == null && !isQuery)
            return null;

        String contextNormalized = normalized;
        if (shouldUseHistory(normalized, amount) && history != null && !history.isEmpty()) {
            String lastUser = lastUserMessage(history);
            if (lastUser != null && !lastUser.isBlank()) {
                contextNormalized = normalizeText((lastUser + " " + lower).toLowerCase(Locale.ROOT));
            }
        }

        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            if (isDelete) {
                AiParsedTarget target = new AiParsedTarget();
                target.amount = amount;
                target.noteKeywords = message.trim();
                AiParseResult res = new AiParseResult();
                res.intent = "DELETE";
                res.target = target;
                return res;
            }
            if (isUpdate) {
                AiParseResult res = new AiParseResult();
                res.intent = "UPDATE";
                res.target = new AiParsedTarget();
                
                // Thử tách chuỗi dựa trên các từ khóa chuyển đổi (bao gồm cả tiếng Việt có dấu)
                String[] parts = lower.split("\\b(thanh|thành|sang|thay|den|đến|to|into|as)\\b");
                if (parts.length >= 2) {
                    // Phần 1 là thông tin cũ
                    BigDecimal oldAmount = extractAmount(parts[0]);
                    res.target.amount = oldAmount != null ? oldAmount : amount;
                    res.target.noteKeywords = parts[0].replaceAll("\\b(sua|sửa|doi|đổi|cap nhat|cập nhật)\\b", "").trim();
                    
                    LocalDate detectDate = detectSingleDate(parts[0], null);
                    if (detectDate != null) res.target.date = detectDate.format(DATE_FMT);
                    
                    // Phần 2 là thông tin mới
                    AiParsedEntry newEntry = new AiParsedEntry();
                    newEntry.amount = extractAmount(parts[1]);
                    
                    // Chỉ lấy note mới nếu có chữ thực sự
                    String notePart = parts[1].replaceAll("[0-9.,dđkK]+", " ").trim();
                    if (notePart.length() > 2) {
                        newEntry.note = parts[1].trim();
                    }
                    
                    String catMatch = detectCategory(normalizeText(parts[1]));
                    if (catMatch != null) {
                        newEntry.categoryName = catMatch;
                    }
                    res.entries = List.of(newEntry);
                } else {
                    res.target.amount = amount;
                }
                return res;
            }

            AiParsedEntry entry = new AiParsedEntry();
            entry.amount = amount;
            entry.type = detectEntryType(contextNormalized);
            entry.categoryName = detectCategory(contextNormalized);
            entry.note = message.trim();
            entry.date = detectSingleDate(normalized, LocalDate.now()).format(DATE_FMT);

            AiParseResult res = new AiParseResult();
            res.intent = "INSERT";
            res.entries = List.of(entry);
            return res;
        }

        AiParsedQuery query = new AiParsedQuery();
        query.metric = detectMetric(contextNormalized);
        query.type = detectQueryType(contextNormalized);
        DateRange range = detectDateRange(normalized, LocalDate.now());
        if (range == null)
            range = detectDateRange(contextNormalized, LocalDate.now());
        if (range != null) {
            query.startDate = range.start.format(DATE_FMT);
            query.endDate = range.end.format(DATE_FMT);
        }

        AiParseResult result = new AiParseResult();
        result.intent = "QUERY";
        result.query = query;
        return result;
    }

    private static String detectMetric(String normalized) {
        if (containsAny(normalized, List.of("nhieu nhat", "cao nhat", "most", "highest", "top")))
            return "TOP_CATEGORY";
        if (containsAny(normalized, List.of("danh sach", "liet ke", "list", "show", "detail", "details")))
            return "LIST";
        if (containsAny(normalized, List.of("trung binh", "average", "avg", "per day")))
            return "AVERAGE";
        if (containsAny(normalized, List.of("xu huong", "tang", "giam", "trend", "increase", "decrease", "compared")))
            return "TREND";
        if (containsAny(normalized, List.of("ty le", "phan tram", "percentage", "percent", "ratio")))
            return "PERCENTAGE";
        return "TOTAL";
    }

    private static String detectQueryType(String normalized) {
        if (containsAny(normalized, List.of(
                "thu", "thu nhap", "luong", "thuong", "nhan tien",
                "nap", "nap tien", "nap vao", "vao vi", "chuyen vao", "tien ve",
                "hoan tien", "refund", "nap vi",
                "income", "salary", "wage", "bonus", "receive", "received", "deposit", "top up", "topup", "transfer in", "cashback")))
            return "INCOME";
        if (containsAny(normalized, List.of("chi", "tieu", "mua", "expense", "spend", "spent", "buy", "purchase", "pay", "payment")))
            return "EXPENSE";
        return "EXPENSE";
    }

    private static String detectEntryType(String normalized) {
        return detectQueryType(normalized);
    }

    private static String detectCategory(String normalized) {
        Map<String, String> map = categoryKeywordMap();
        for (var entry : map.entrySet()) {
            if (normalized.contains(entry.getKey()))
                return entry.getValue();
        }
        return null;
    }

    private static DateRange detectDateRange(String normalized, LocalDate today) {
        if (normalized.contains("hom nay") || normalized.contains("vua nay") || normalized.contains("vua moi") ||
            normalized.contains("chieu nay") || normalized.contains("sang nay") || normalized.contains("toi nay") ||
            normalized.contains("today") || normalized.contains("this morning") || normalized.contains("this afternoon") ||
            normalized.contains("this evening") || normalized.contains("tonight")) {
            return new DateRange(today, today);
        }
        if (normalized.contains("hom qua") || normalized.contains("yesterday")) {
            LocalDate d = today.minusDays(1);
            return new DateRange(d, d);
        }
        if (normalized.contains("this week")) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new DateRange(start, today);
        }
        if (normalized.contains("last week")) {
            LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = thisWeekStart.minusDays(1);
            LocalDate start = end.minusDays(6);
            return new DateRange(start, end);
        }
        if (normalized.contains("thang nay") || normalized.contains("this month") || normalized.contains("current month")) {
            LocalDate start = today.withDayOfMonth(1);
            return new DateRange(start, today);
        }
        if (normalized.contains("thang truoc") || normalized.contains("last month") || normalized.contains("previous month")) {
            LocalDate prev = today.minusMonths(1);
            LocalDate start = prev.withDayOfMonth(1);
            LocalDate end = prev.withDayOfMonth(prev.lengthOfMonth());
            return new DateRange(start, end);
        }
        if (normalized.contains("nam nay") || normalized.contains("this year")) {
            LocalDate start = LocalDate.of(today.getYear(), 1, 1);
            return new DateRange(start, today);
        }
        if (normalized.contains("last year") || normalized.contains("previous year")) {
            int year = today.getYear() - 1;
            LocalDate start = LocalDate.of(year, 1, 1);
            LocalDate end = LocalDate.of(year, 12, 31);
            return new DateRange(start, end);
        }
        return null;
    }

    private static LocalDate detectSingleDate(String raw, LocalDate fallback) {
        if (raw == null) return fallback;
        String normalized = normalizeText(raw.toLowerCase(Locale.ROOT));
        LocalDate today = LocalDate.now();
        DateRange range = detectDateRange(normalized, today);
        if (range != null)
            return range.start;
        return fallback;
    }

    private static BigDecimal extractAmount(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT).trim();

        // 1. Handle "X triệu Y" (e.g., "1 triệu 2", "1tr2")
        Matcher trMixedMatch = Pattern.compile("(\\d+)\\s*(?:tr(?:ieu|iệu)?|t)\\s*(\\d+)?\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (trMixedMatch.find()) {
            BigDecimal main = new BigDecimal(trMixedMatch.group(1)).multiply(new BigDecimal("1000000"));
            String subStr = trMixedMatch.group(2);
            if (subStr != null && !subStr.isEmpty()) {
                BigDecimal sub = new BigDecimal(subStr);
                // "1 triệu 2" is usually 1,200,000, not 1,000,002
                if (sub.compareTo(new BigDecimal("1000")) < 0) {
                     if (subStr.length() == 1) sub = sub.multiply(new BigDecimal("100000"));
                     else if (subStr.length() == 2) sub = sub.multiply(new BigDecimal("10000"));
                     else if (subStr.length() == 3) sub = sub.multiply(new BigDecimal("1000"));
                }
                return main.add(sub);
            }
            return main;
        }

        // 2. Handle "X trăm Y" (e.g., "2 trăm 3", "2 trăm rưỡi", "2 trăm 35")
        // Note: In VN, "2 trăm" in expense context usually means 200,000
        Matcher tramMatch = Pattern.compile("(\\d+)\\s*(?:tram|trăm)\\s*((\\d+)|ruoi|rưỡi)?\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (tramMatch.find()) {
            BigDecimal main = new BigDecimal(tramMatch.group(1)).multiply(new BigDecimal("100000"));
            String subStr = tramMatch.group(3);
            if (tramMatch.group(2) != null && (tramMatch.group(2).contains("ruoi") || tramMatch.group(2).contains("rưỡi"))) {
                return main.add(new BigDecimal("50000"));
            }
            if (subStr != null && !subStr.isEmpty()) {
                BigDecimal sub = new BigDecimal(subStr);
                if (sub.compareTo(new BigDecimal("100")) < 0) {
                    if (subStr.length() == 1) sub = sub.multiply(new BigDecimal("10000"));
                    else if (subStr.length() == 2) sub = sub.multiply(new BigDecimal("1000"));
                }
                return main.add(sub);
            }
            return main;
        }

        // 3. Handle "X tỷ Y" (e.g., "1 tỷ 2")
        Matcher tyMixedMatch = Pattern.compile("(\\d+)\\s*(?:ty|tỷ)\\s*(\\d+)?\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (tyMixedMatch.find()) {
            BigDecimal main = new BigDecimal(tyMixedMatch.group(1)).multiply(new BigDecimal("1000000000"));
            String subStr = tyMixedMatch.group(2);
            if (subStr != null && !subStr.isEmpty()) {
                BigDecimal sub = new BigDecimal(subStr);
                if (sub.compareTo(new BigDecimal("1000")) < 0) {
                     if (subStr.length() == 1) sub = sub.multiply(new BigDecimal("100000000"));
                     else if (subStr.length() == 2) sub = sub.multiply(new BigDecimal("10000000"));
                     else if (subStr.length() == 3) sub = sub.multiply(new BigDecimal("1000000"));
                }
                return main.add(sub);
            }
            return main;
        }

        // 4. Handle "X rưỡi" (unit specific)
        if (lower.contains(" rưỡi") || lower.contains(" ruoi")) {
            Matcher ruoiMatch = Pattern.compile("(\\d+)\\s*(k|tr(?:ieu|iệu)?|t|ty|tỷ)?\\s*(?:ruoi|rưỡi)\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
            if (ruoiMatch.find()) {
                BigDecimal main = new BigDecimal(ruoiMatch.group(1));
                String unit = ruoiMatch.group(2);
                if (unit == null || unit.isEmpty() || unit.equalsIgnoreCase("k")) {
                    return main.multiply(new BigDecimal("1000")).add(new BigDecimal("500"));
                } else if (unit.matches("(?i)tr(?:ieu|iệu)?|t")) {
                    return main.multiply(new BigDecimal("1000000")).add(new BigDecimal("500000"));
                } else if (unit.matches("(?i)ty|tỷ")) {
                    return main.multiply(new BigDecimal("1000000000")).add(new BigDecimal("500000000"));
                }
            }
        }

        // 5. Standard k/tr/ty units
        Matcher kMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*k\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (kMatch.find()) {
            BigDecimal num = parseDecimal(kMatch.group(1));
            return num != null ? num.multiply(new BigDecimal("1000")) : null;
        }
        Matcher trMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:tr(?:ieu|iệu)?|t)\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (trMatch.find()) {
            BigDecimal num = parseDecimal(trMatch.group(1));
            return num != null ? num.multiply(new BigDecimal("1000000")) : null;
        }
        Matcher tyMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:ty|tỷ)\\b", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (tyMatch.find()) {
            BigDecimal num = parseDecimal(tyMatch.group(1));
            return num != null ? num.multiply(new BigDecimal("1000000000")) : null;
        }

        // 6. Standard number with currency suffix or no suffix
        Matcher numMatch = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?)\\s*(?:đ|d|vnd|vnđ)?", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (numMatch.find()) {
            String raw = numMatch.group(1);
            String cleaned = raw.replaceAll("[.,]", "");
            try {
                BigDecimal val = new BigDecimal(cleaned);
                // Heuristic for small numbers in common contexts
                if (val.compareTo(new BigDecimal("1000")) < 0 && (lower.contains("ăn") || lower.contains("uống") || lower.contains("phở") || lower.contains("cơm") || lower.contains("mì") || lower.contains("mi"))) {
                    return val.multiply(new BigDecimal("1000"));
                }
                return val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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

    private static boolean containsAny(String text, List<String> keywords) {
        for (String k : keywords) {
            if (text.contains(k))
                return true;
        }
        return false;
    }

    private static String normalizeText(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}+", "");
        normalized = normalized.replace('đ', 'd');
        return normalized;
    }

    private static Map<String, String> categoryKeywordMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("an", "Ăn uống");
        map.put("nhau", "Ăn uống");
        map.put("tra", "Ăn uống");
        map.put("sua", "Ăn uống");
        map.put("pho", "Ăn uống");
        map.put("com", "Ăn uống");
        map.put("bun", "Ăn uống");
        map.put("my", "Ăn uống");
        map.put("mi", "Ăn uống");
        map.put("banh", "Ăn uống");
        map.put("nuoc", "Ăn uống");
        map.put("ca phe", "Ăn uống");
        map.put("cafe", "Ăn uống");
        map.put("uong", "Ăn uống");
        map.put("food", "Ăn uống");
        map.put("meal", "Ăn uống");
        map.put("lunch", "Ăn uống");
        map.put("dinner", "Ăn uống");
        map.put("breakfast", "Ăn uống");
        map.put("restaurant", "Ăn uống");
        map.put("coffee", "Ăn uống");
        map.put("tea", "Ăn uống");
        map.put("drink", "Ăn uống");
        map.put("milk tea", "Ăn uống");
        map.put("snack", "Ăn uống");
        map.put("xang", "Xăng xe");
        map.put("do xang", "Xăng xe");
        map.put("dau", "Xăng xe");
        map.put("xe", "Xăng xe");
        map.put("o to", "Xăng xe");
        map.put("oto", "Xăng xe");
        map.put("xe may", "Xăng xe");
        map.put("gas", "Xăng xe");
        map.put("gasoline", "Xăng xe");
        map.put("petrol", "Xăng xe");
        map.put("fuel", "Xăng xe");
        map.put("toll", "Xăng xe");
        map.put("taxi", "Xăng xe");
        map.put("uber", "Xăng xe");
        map.put("grab", "Xăng xe");
        map.put("transport", "Xăng xe");
        map.put("transportation", "Xăng xe");
        map.put("mua", "Mua sắm");
        map.put("sam", "Mua sắm");
        map.put("quan ao", "Mua sắm");
        map.put("giay", "Mua sắm");
        map.put("tui", "Mua sắm");
        map.put("my pham", "Mua sắm");
        map.put("sieu thi", "Mua sắm");
        map.put("cho", "Mua sắm");
        map.put("shopee", "Mua sắm");
        map.put("lazada", "Mua sắm");
        map.put("tiki", "Mua sắm");
        map.put("shopping", "Mua sắm");
        map.put("groceries", "Mua sắm");
        map.put("supermarket", "Mua sắm");
        map.put("mall", "Mua sắm");
        map.put("clothes", "Mua sắm");
        map.put("purchase", "Mua sắm");
        map.put("buy", "Mua sắm");
        map.put("giai tri", "Giải trí");
        map.put("phim", "Giải trí");
        map.put("rap", "Giải trí");
        map.put("game", "Giải trí");
        map.put("du lich", "Giải trí");
        map.put("choi", "Giải trí");
        map.put("sinh nhat", "Giải trí");
        map.put("qua tang", "Giải trí");
        map.put("entertainment", "Giải trí");
        map.put("movie", "Giải trí");
        map.put("cinema", "Giải trí");
        map.put("netflix", "Giải trí");
        map.put("gift", "Giải trí");
        map.put("present", "Giải trí");
        map.put("y te", "Y tế");
        map.put("thuoc", "Y tế");
        map.put("siro", "Y tế");
        map.put("kham", "Y tế");
        map.put("benh vien", "Y tế");
        map.put("bac si", "Y tế");
        map.put("health", "Y tế");
        map.put("medical", "Y tế");
        map.put("hospital", "Y tế");
        map.put("medicine", "Y tế");
        map.put("pharmacy", "Y tế");
        map.put("hoc", "Giáo dục");
        map.put("sach", "Giáo dục");
        map.put("hoc phi", "Giáo dục");
        map.put("khoa hoc", "Giáo dục");
        map.put("education", "Giáo dục");
        map.put("school", "Giáo dục");
        map.put("tuition", "Giáo dục");
        map.put("book", "Giáo dục");
        map.put("course", "Giáo dục");
        map.put("study", "Giáo dục");
        map.put("nap vi", "Nạp tiền");
        map.put("nap tien", "Nạp tiền");
        map.put("thu nhap", "Nạp tiền");
        map.put("luong", "Nạp tiền");
        map.put("tien ve", "Nạp tiền");
        map.put("thuong", "Nạp tiền");
        map.put("income", "Nạp tiền");
        map.put("salary", "Nạp tiền");
        map.put("wage", "Nạp tiền");
        map.put("bonus", "Nạp tiền");
        map.put("deposit", "Nạp tiền");
        map.put("top up", "Nạp tiền");
        map.put("topup", "Nạp tiền");
        map.put("cashback", "Nạp tiền");
        map.put("transfer in", "Nạp tiền");
        map.put("gui xe", "Gửi xe");
        map.put("parking", "Gửi xe");
        map.put("ve xe", "Gửi xe");
        map.put("thue", "Nhà cửa");
        map.put("dien", "Nhà cửa");
        map.put("nuoc", "Nhà cửa");
        map.put("internet", "Nhà cửa");
        map.put("wifi", "Nhà cửa");
        map.put("giat", "Nhà cửa");
        map.put("thue nha", "Nhà cửa");
        map.put("rent", "Nhà cửa");
        map.put("nap vao", "Nạp tiền");
        map.put("vao vi", "Nạp tiền");
        map.put("chuyen vao", "Nạp tiền");
        map.put("hoan tien", "Nạp tiền");
        map.put("refund", "Nạp tiền");
        map.put("tien ve", "Nạp tiền");
        return map;
    }

    private static class DateRange {
        final LocalDate start;
        final LocalDate end;

        DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class AccountResolution {
        final Long accountId;
        final boolean needsSelection;
        final String errorMessage;

        private AccountResolution(Long accountId, boolean needsSelection, String errorMessage) {
            this.accountId = accountId;
            this.needsSelection = needsSelection;
            this.errorMessage = errorMessage;
        }

        static AccountResolution selected(Long accountId) {
            return new AccountResolution(accountId, false, null);
        }

        static AccountResolution needsSelection() {
            return new AccountResolution(null, true, null);
        }

        static AccountResolution none() {
            return new AccountResolution(null, false, null);
        }

        static AccountResolution error(String message) {
            return new AccountResolution(null, false, message);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AiParseResult {
        public String intent;
        public String adviceReply;
        public AiParsedQuery query;
        public AiParsedTarget target;
        public List<AiParsedEntry> entries;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AiParsedTarget {
        public BigDecimal amount;
        public String categoryName;
        public String date;
        public String noteKeywords;
        public Boolean deleteAll;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AiParsedQuery {
        public String metric;
        public String type;
        public String startDate;
        public String endDate;
        public Integer limit;
        public String categoryName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AiParsedEntry {
        public BigDecimal amount;
        public String categoryName;
        public String note;
        public String type;
        public String date;
    }
}
