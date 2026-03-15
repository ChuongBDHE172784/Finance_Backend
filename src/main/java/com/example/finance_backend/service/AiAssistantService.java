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
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Locale VI_LOCALE = new Locale("vi", "VN");
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

    public AiAssistantResponse handle(AiAssistantRequest request) {
        final String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        if (message.isEmpty()) {
            AiAssistantResponse response = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply("Bạn hãy nhập câu hỏi hoặc nội dung chi tiêu để mình xử lý nhé.")
                    .build();
            response.setConversationId(conversationId);
            return response;
        }

        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        AiParseResult parsed = null;
        Exception geminiError = null;
        try {
            parsed = parseWithGemini(message, history);
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
                        .reply("Mình chưa kết nối được AI. Hãy kiểm tra GEMINI/GOOGLE API key ở backend và thử lại.")
                        .build();
                return finalizeResponse(response, conversationId, message);
            }
        }

        if (parsed == null || parsed.intent == null) {
            AiAssistantResponse response = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply("Mình chưa hiểu rõ ý bạn. Hãy thử: \"Tháng này tôi tiêu nhiều nhất vào cái gì?\" hoặc \"Hôm nay tôi ăn phở 45k\".")
                    .build();
            return finalizeResponse(response, conversationId, message);
        }

        final String intent = parsed.intent.trim().toUpperCase(Locale.ROOT);
        if ("INSERT".equals(intent)) {
            AiAssistantResponse response = handleInsert(message, parsed, request.getAccountId());
            return finalizeResponse(response, conversationId, message);
        }
        if ("QUERY".equals(intent)) {
            AiAssistantResponse response = handleQuery(parsed);
            return finalizeResponse(response, conversationId, message);
        }
        if ("UPDATE".equals(intent)) {
            AiAssistantResponse response = handleUpdate(parsed, message, request.getAccountId());
            return finalizeResponse(response, conversationId, message);
        }
        if ("DELETE".equals(intent)) {
            AiAssistantResponse response = handleDelete(parsed, message);
            return finalizeResponse(response, conversationId, message);
        }
        if ("ADVICE".equals(intent)) {
            AiAssistantResponse response = handleAdvice(parsed);
            return finalizeResponse(response, conversationId, message);
        }

        AiAssistantResponse response = AiAssistantResponse.builder()
                .intent("UNKNOWN")
                .reply("Mình chưa hiểu rõ ý bạn. Hãy thử: \"Tháng trước tôi tiêu bao nhiêu\", \"Hôm nay tôi ăn phở 45k\" hoặc \"Xóa giao dịch 45k vừa rồi\".")
                .build();
        return finalizeResponse(response, conversationId, message);
    }

    private AiParseResult parseWithGemini(String message, List<AiMessage> history) throws JsonProcessingException {
        String prompt = buildPrompt(message, history);
        GenerateContentResponse response = getClient().models.generateContent(model, prompt, null);
        String text = response.text();
        if (text == null) {
            return null;
        }
        String json = extractJson(text);
        ObjectMapper mapper = objectMapper.copy();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(json, AiParseResult.class);
    }

    private AiAssistantResponse handleInsert(String originalMessage, AiParseResult parsed, Long forcedAccountId) {
        List<AiParsedEntry> entries = parsed.entries == null ? List.of() : parsed.entries;
        if (entries.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .refreshRequired(false)
                    .reply("Mình chưa thấy khoản chi/thu nào rõ ràng. Bạn thử ghi: \"Hôm nay ăn phở 45k\" nhé.")
                    .build();
        }
        AccountResolution accountResolution = resolveAccount(originalMessage, forcedAccountId);
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
                    .reply("Bạn muốn dùng ví nào cho giao dịch này?")
                    .build();
        }
        if (accountResolution.accountId == null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply("Bạn chưa có tài khoản/ví. Hãy tạo tài khoản trước khi thêm giao dịch nhé.")
                    .build();
        }
        Long accountId = accountResolution.accountId;

        Map<String, Long> nameToId = getNameToIdMap();
        Long fallbackCategoryId = resolveFallbackCategoryId(nameToId);
        if (fallbackCategoryId == null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply("Chưa có danh mục chi tiêu nào. Hãy tạo danh mục trước nhé.")
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
                FinancialEntryDto created = entryService.create(req);
                createdCount++;
                String catName = created.getCategoryName() != null ? created.getCategoryName() : "";
                String sign = "INCOME".equals(type) ? "+" : "-";
                savedLines.add(String.format("%s %s • %s", sign, formatVnd(entry.amount), catName));
            } catch (Exception ex) {
                log.error("Failed to create entry from AI parse: {}", req, ex);
                errors.add(ex.getMessage() != null ? ex.getMessage() : "Lỗi không xác định");
            }
        }

        if (createdCount == 0) {
            String errorText = errors.isEmpty()
                    ? "Mình chưa thể tạo giao dịch. Hãy thử nhập rõ số tiền và nội dung nhé."
                    : "Không thể lưu giao dịch: " + errors.get(0);
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .refreshRequired(false)
                    .reply(errorText)
                    .build();
        }

        StringBuilder reply = new StringBuilder();
        reply.append("Đã lưu ").append(createdCount).append(" giao dịch.");
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

    private AiAssistantResponse handleQuery(AiParseResult parsed) {
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
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, q.categoryName, null);
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
                    .reply("Không có giao dịch nào trong khoảng thời gian này.")
                    .build();
        }

        String metric = q.metric == null ? "TOTAL" : q.metric.toUpperCase(Locale.ROOT);
        switch (metric) {
            case "TOP_CATEGORY":
                return buildTopCategoryReply(start, end, entries);
            case "LIST":
                return buildListReply(start, end, entries, q.limit);
            case "AVERAGE":
                return buildAverageReply(start, end, entries);
            case "TREND":
                return buildTrendReply(start, end, typeFilter);
            case "PERCENTAGE":
                return buildPercentageReply(start, end, entries);
            case "TOTAL":
            default:
                return buildTotalReply(start, end, entries, typeFilter);
        }
    }

    private AiAssistantResponse buildAverageReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries) {
        BigDecimal total = entries.stream()
                .map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);
        BigDecimal average = total.divide(new BigDecimal(days), 0, RoundingMode.HALF_UP);

        String reply = String.format("Trong khoảng từ %s đến %s (%d ngày), trung bình mỗi ngày bạn chi %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), days, formatVnd(average));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildTrendReply(LocalDate start, LocalDate end, String typeFilter) {
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

        String label = "INCOME".equals(typeFilter) ? "thu" : "chi";
        String trend = "không đổi";
        if (prevTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = currentTotal.subtract(prevTotal);
            BigDecimal percent = diff.multiply(new BigDecimal("100")).divide(prevTotal, 1, RoundingMode.HALF_UP);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                trend = "tăng " + percent + "%";
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                trend = "giảm " + percent.abs() + "%";
            }
        } else if (currentTotal.compareTo(BigDecimal.ZERO) > 0) {
            trend = "mới phát sinh";
        }

        String reply = String.format("So với giai đoạn trước (%s đến %s), tổng %s của bạn %s. (Hiện tại: %s, Trước đó: %s)",
                prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend, formatVnd(currentTotal),
                formatVnd(prevTotal));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildPercentageReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries) {
        BigDecimal total = entries.stream()
                .map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return AiAssistantResponse.builder().intent("QUERY").reply("Không có dữ liệu để tính tỷ lệ.").build();
        }

        Map<Long, BigDecimal> catTotals = new HashMap<>();
        for (var e : entries) {
            catTotals.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Tỷ lệ chi tiêu từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));

        catTotals.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .forEach(entry -> {
                    BigDecimal pct = entry.getValue().multiply(new BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP);
                    sb.append(String.format("- %s: %s%% (%s)\n", idToName.getOrDefault(entry.getKey(), "Khác"), pct,
                            formatVnd(entry.getValue())));
                });

        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(sb.toString().trim())
                .build();
    }

    private AiAssistantResponse handleUpdate(AiParseResult parsed, String originalMessage, Long forcedAccountId) {
        List<com.example.finance_backend.entity.FinancialEntry> targets = findTargetEntries(parsed.target);
        if (targets.isEmpty()) {
            return AiAssistantResponse.builder().intent("UPDATE").reply("Mình không tìm thấy giao dịch nào khớp để sửa.").build();
        }
        if (targets.size() > 1) {
            return AiAssistantResponse.builder().intent("UPDATE").reply("Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: ngày hoặc số tiền cụ thể).").build();
        }

        com.example.finance_backend.entity.FinancialEntry entry = targets.get(0);
        AiParsedEntry newData = (parsed.entries != null && !parsed.entries.isEmpty()) ? parsed.entries.get(0) : null;

        if (newData == null) {
            // Thử cứu vãn (salvage) dữ liệu nếu Gemini hụt nhưng message có pattern "thành"
            String[] parts = originalMessage.toLowerCase(VI_LOCALE).split("\\b(thanh|thành|sang|thay|den|đến)\\b");
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
            return AiAssistantResponse.builder().intent("UPDATE").reply("Bạn muốn sửa thông tin gì của giao dịch này?").build();
        }

        if (newData.amount != null) entry.setAmount(newData.amount);
        if (newData.note != null && !newData.note.isBlank()) entry.setNote(newData.note);
        if (newData.date != null && !newData.date.isBlank()) {
             LocalDate d = parseDate(newData.date, null);
             if (d != null) entry.setTransactionDate(d);
        }
        if (newData.categoryName != null && !newData.categoryName.isBlank()) {
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, newData.categoryName, entry.getCategoryId());
            entry.setCategoryId(catId);
        }

        entryRepository.save(entry);
        return AiAssistantResponse.builder()
                .intent("UPDATE")
                .refreshRequired(true)
                .reply("Đã cập nhật giao dịch: " + formatVnd(entry.getAmount()) + " - " + entry.getNote())
                .build();
    }

    private AiAssistantResponse handleDelete(AiParseResult parsed, String originalMessage) {
        List<com.example.finance_backend.entity.FinancialEntry> targets = findTargetEntries(parsed.target);
        if (targets.isEmpty()) {
            return AiAssistantResponse.builder().intent("DELETE").reply("Mình không tìm thấy giao dịch nào khớp để xóa.").build();
        }

        boolean deleteAll = parsed.target != null && Boolean.TRUE.equals(parsed.target.deleteAll);
        if (!deleteAll && targets.size() > 1) {
            return AiAssistantResponse.builder().intent("DELETE").reply("Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: 'Xóa tất cả' hoặc số tiền cụ thể).").build();
        }

        int deletedCount = targets.size();
        entryRepository.deleteAll(targets);

        String reply = deletedCount == 1 
            ? "Đã xóa giao dịch: " + formatVnd(targets.get(0).getAmount()) + " - " + targets.get(0).getNote()
            : "Đã xóa " + deletedCount + " giao dịch khớp với yêu cầu.";

        return AiAssistantResponse.builder()
                .intent("DELETE")
                .refreshRequired(true)
                .reply(reply)
                .build();
    }

    private AiAssistantResponse handleAdvice(AiParseResult parsed) {
        String reply = parsed.adviceReply != null ? parsed.adviceReply : "Mình có thể giúp gì cho bạn về quản lý tài chính?";
        return AiAssistantResponse.builder()
                .intent("ADVICE")
                .reply(reply)
                .build();
    }

    private List<com.example.finance_backend.entity.FinancialEntry> findTargetEntries(AiParsedTarget target) {
        if (target == null) return List.of();
        LocalDate searchDate = parseDate(target.date, null);
        
        // Nếu không có ngày cụ thể, mở rộng phạm vi ra 30 ngày để dễ tìm hơn
        LocalDate start = searchDate != null ? searchDate : LocalDate.now().minusDays(30);
        LocalDate end = searchDate != null ? searchDate : LocalDate.now();

        List<com.example.finance_backend.entity.FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream()
                .filter(e -> {
                    if (target.amount != null && e.getAmount().compareTo(target.amount) != 0) return false;
                    if (target.noteKeywords != null && !target.noteKeywords.isBlank()) {
                        String note = e.getNote() != null ? normalizeText(e.getNote().toLowerCase(VI_LOCALE)) : "";
                        String keywords = normalizeText(target.noteKeywords.toLowerCase(VI_LOCALE));
                        
                        // Loại bỏ STOP WORDS
                        keywords = keywords.replaceAll("\\b(sua|sửa|doi|đổi|cap nhat|cập nhật|thanh|thành|sang|thay|den|đến|xoa|huy|bo|giup|giao dich|khoan|cai|nay|tat ca|het|hom nay|hom qua|ngay|thang|nam|vi|momo|tien|chi|tieu|vua|nay|chieu|sang|toi)\\b", "")
                                        .replaceAll("\\s+", " ")
                                        .trim();
                        
                        // LOẠI BỎ TẤT CẢ CON SỐ, DẤU CHẤM, DẤU PHẨY (vì user có thể viết 200k, 200.000 trong khi note có thể khác)
                        keywords = keywords.replaceAll("[0-9.,dđkK]+", " ")
                                        .replaceAll("\\s+", " ")
                                        .trim();
                                        
                        if (!keywords.isBlank() && !note.contains(keywords)) return false;
                    }
                    if (target.categoryName != null && !target.categoryName.isBlank()) {
                        String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "").toLowerCase(VI_LOCALE);
                        if (!catName.contains(target.categoryName.toLowerCase(VI_LOCALE))) return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private AiAssistantResponse buildTotalReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            String typeFilter) {
        BigDecimal total = entries.stream()
                .map(com.example.finance_backend.entity.FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String label = "ALL".equals(typeFilter) ? "Tổng giao dịch"
                : ("INCOME".equals(typeFilter) ? "Tổng thu" : "Tổng chi");
        String reply = String.format("%s từ %s đến %s là %s.",
                label,
                start.format(DATE_FMT),
                end.format(DATE_FMT),
                formatVnd(total));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildTopCategoryReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (var e : entries) {
            totals.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }
        var max = totals.entrySet().stream()
                .max(Comparator.comparing(e -> e.getValue()));
        if (max.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("QUERY")
                    .reply("Chưa có dữ liệu để tính nhóm chi tiêu lớn nhất.")
                    .build();
        }
        Long catId = max.get().getKey();
        String catName = categoryService.getIdToNameMap().getOrDefault(catId, "Khác");
        String reply = String.format("Trong khoảng %s đến %s, bạn chi nhiều nhất vào %s: %s.",
                start.format(DATE_FMT),
                end.format(DATE_FMT),
                catName,
                formatVnd(max.get().getValue()));
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse buildListReply(LocalDate start, LocalDate end,
            List<com.example.finance_backend.entity.FinancialEntry> entries,
            Integer limit) {
        int max = (limit == null || limit <= 0) ? 10 : Math.min(limit, 20);
        List<com.example.finance_backend.entity.FinancialEntry> slice = entries.stream()
                .limit(max)
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Danh sách giao dịch từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));
        for (var e : slice) {
            String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "Khác");
            String note = e.getNote() != null ? e.getNote() : "";
            sb.append(String.format("- %s • %s", formatVnd(e.getAmount()), catName));
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

    private String buildPrompt(String message, List<AiMessage> history) {
        LocalDate today = LocalDate.now();
        List<String> categories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.toList());
        String categoriesStr = String.join(", ", categories);
        String historyBlock = formatHistory(history);

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
                """.formatted(today.format(DATE_FMT), categoriesStr, historyBlock, message);
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

    private static String formatVnd(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
        return NumberFormat.getInstance(VI_LOCALE).format(normalized) + " đ";
    }

    private static String trimNote(String note) {
        if (note == null)
            return "";
        String trimmed = note.trim();
        if (trimmed.length() <= 80)
            return trimmed;
        return trimmed.substring(0, 77) + "...";
    }

    private AccountResolution resolveAccount(String message, Long forcedAccountId) {
        List<Account> accounts = accountRepository.findAll();
        if (accounts.isEmpty()) {
            return AccountResolution.none();
        }
        if (forcedAccountId != null) {
            boolean exists = accounts.stream().anyMatch(a -> Objects.equals(a.getId(), forcedAccountId));
            if (exists) {
                return AccountResolution.selected(forcedAccountId);
            }
            return AccountResolution.error("Ví/tài khoản không tồn tại.");
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

    private AiAssistantResponse finalizeResponse(AiAssistantResponse response, String conversationId, String message) {
        response.setConversationId(conversationId);
        if (message != null && !message.isBlank()) {
            saveMessage(conversationId, "USER", message);
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

    private static String formatHistory(List<AiMessage> history) {
        if (history == null || history.isEmpty())
            return "Không có.";
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
                "hom qua", "hom nay"));
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
        if (trimmed.equalsIgnoreCase("Nạp ví") || trimmed.equalsIgnoreCase("Nap vi")) {
            return "Nạp tiền";
        }
        return trimmed;
    }

    private AiParseResult parseFallback(String message, List<AiMessage> history) {
        String lower = message.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty())
            return null;

        String normalized = normalizeText(lower);
        boolean isDelete = containsAny(normalized, List.of("xoa", "huy", "bo qua"));
        boolean isUpdate = containsAny(normalized, List.of("sua", "doi", "cap nhat", "thanh"));
        boolean isAll = normalized.contains("tat ca") || normalized.contains("het");

        if (isDelete && !normalized.contains("bao nhieu")) { // Tránh nhầm với Query kiểu "Tuần này xóa bao nhiêu" (nếu có)
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
                String kw = lower.replaceAll("\\b(xoa|huy|bo|giup|giao dich|khoan|cai|nay|hom nay|hom qua|ngay|thang|nam|vi|momo)\\b", "")
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
                "danh sach", "liet ke", "thang nay", "thang truoc", "hom nay", "hom qua", "nam nay"));

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
                String[] parts = lower.split("\\b(thanh|thành|sang|thay|den|đến)\\b");
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
        if (containsAny(normalized, List.of("nhieu nhat", "cao nhat")))
            return "TOP_CATEGORY";
        if (containsAny(normalized, List.of("danh sach", "liet ke")))
            return "LIST";
        return "TOTAL";
    }

    private static String detectQueryType(String normalized) {
        if (containsAny(normalized, List.of(
                "thu", "thu nhap", "luong", "thuong", "nhan tien",
                "nap", "nap tien", "nap vao", "vao vi", "chuyen vao", "tien ve",
                "hoan tien", "refund", "nap vi")))
            return "INCOME";
        if (containsAny(normalized, List.of("chi", "tieu", "mua")))
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
            normalized.contains("chieu nay") || normalized.contains("sang nay") || normalized.contains("toi nay")) {
            return new DateRange(today, today);
        }
        if (normalized.contains("hom qua")) {
            LocalDate d = today.minusDays(1);
            return new DateRange(d, d);
        }
        if (normalized.contains("thang nay")) {
            LocalDate start = today.withDayOfMonth(1);
            return new DateRange(start, today);
        }
        if (normalized.contains("thang truoc")) {
            LocalDate prev = today.minusMonths(1);
            LocalDate start = prev.withDayOfMonth(1);
            LocalDate end = prev.withDayOfMonth(prev.lengthOfMonth());
            return new DateRange(start, end);
        }
        if (normalized.contains("nam nay")) {
            LocalDate start = LocalDate.of(today.getYear(), 1, 1);
            return new DateRange(start, today);
        }
        return null;
    }

    private static LocalDate detectSingleDate(String raw, LocalDate fallback) {
        if (raw == null) return fallback;
        String normalized = normalizeText(raw.toLowerCase(VI_LOCALE));
        LocalDate today = LocalDate.now();
        DateRange range = detectDateRange(normalized, today);
        if (range != null)
            return range.start;
        return fallback;
    }

    private static BigDecimal extractAmount(String text) {
        Matcher kMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*k\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        if (kMatch.find()) {
            BigDecimal num = parseDecimal(kMatch.group(1));
            return num != null ? num.multiply(new BigDecimal("1000")) : null;
        }
        Matcher trMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*tr(?:ieu|iệu)?\\b", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (trMatch.find()) {
            BigDecimal num = parseDecimal(trMatch.group(1));
            return num != null ? num.multiply(new BigDecimal("1000000")) : null;
        }
        Matcher numMatch = Pattern
                .compile("(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?)\\s*(?:đ|d|vnd|vnđ)?", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (numMatch.find()) {
            String raw = numMatch.group(1);
            String cleaned = raw.replaceAll("[.,]", "");
            try {
                return new BigDecimal(cleaned);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null)
            return null;
        String normalized = raw.replace(",", ".");
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
        map.put("pho", "Ăn uống");
        map.put("com", "Ăn uống");
        map.put("bun", "Ăn uống");
        map.put("tra sua", "Ăn uống");
        map.put("ca phe", "Ăn uống");
        map.put("uong", "Ăn uống");
        map.put("xang", "Xăng xe");
        map.put("do xang", "Xăng xe");
        map.put("gas", "Xăng xe");
        map.put("mua", "Mua sắm");
        map.put("sam", "Mua sắm");
        map.put("sieu thi", "Mua sắm");
        map.put("giai tri", "Giải trí");
        map.put("phim", "Giải trí");
        map.put("game", "Giải trí");
        map.put("sinh nhat", "Giải trí");
        map.put("qua tang", "Giải trí");
        map.put("y te", "Y tế");
        map.put("thuoc", "Y tế");
        map.put("benh vien", "Y tế");
        map.put("hoc", "Giáo dục");
        map.put("sach", "Giáo dục");
        map.put("nap vi", "Nạp tiền");
        map.put("nap tien", "Nạp tiền");
        map.put("thu nhap", "Nạp tiền");
        map.put("luong", "Nạp tiền");
        map.put("gui xe", "Gửi xe");
        map.put("parking", "Gửi xe");
        map.put("thuong", "Nạp tiền");
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
