package com.example.finance_backend.service;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.entity.Category;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.repository.BudgetRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.ai.*;
import com.example.finance_backend.service.ai.FinancialScoreEngine.FinancialScoreResult;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedEntry;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedTarget;
import com.example.finance_backend.service.ai.SpendingAnalyticsService.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bộ điều phối AI Assistant — ủy thác cho các thành phần pipeline:
 * TextPreprocessor → IntentDetector → EntityExtractor →
 * ConversationContextManager
 * → SpendingAnalyticsService → ResponseGenerator
 *
 * Cũng sử dụng GeminiClientWrapper cho các hiểu biết ngôn ngữ tự nhiên (NLU) phức tạp khi các quy tắc thất bại.
 */
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // ── Các thành phần Pipeline ──
    private final TextPreprocessor textPreprocessor;
    private final IntentDetector intentDetector;
    private final EntityExtractor entityExtractor;
    private final ConversationContextManager contextManager;
    private final GeminiClientWrapper geminiClient;
    private final ResponseGenerator responseGenerator;
    private final SpendingAnalyticsService analyticsService;
    private final FinancialScoreEngine financialScoreEngine;

    // ── Dịch vụ dữ liệu ──
    private final FinancialEntryService entryService;
    private final FinancialEntryRepository entryRepository;
    private final AccountRepository accountRepository;
    private final AiMessageRepository aiMessageRepository;
    private final CategoryService categoryService;
    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;

    // ═════════════════════════════════════════════════════════
    // TRẠNG THÁI CUỘC HỘI THOẠI
    // ═════════════════════════════════════════════════════════
    public static class PendingPlanAction {
        public String intent;
        public String category;
        public BigDecimal amount;
        public String month;
        public String awaitingField;
    }
    
    private final Map<String, PendingPlanAction> planningState = new java.util.concurrent.ConcurrentHashMap<>();

    // ═════════════════════════════════════════════════════════
    // ĐIỂM VÀO CHÍNH CỦA PIPELINE
    // ═════════════════════════════════════════════════════════

    public AiAssistantResponse handle(AiAssistantRequest request) {
        final String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        // Bước 1: Tiền xử lý văn bản
        ParsedMessage parsed = textPreprocessor.preprocess(message, request.getLanguage());
        String language = parsed.getLanguage();

        // Kiểm tra đầu vào trống
        if (message.isEmpty() && (request.getBase64Image() == null || request.getBase64Image().isBlank())) {
            AiAssistantResponse resp = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(responseGenerator.emptyInputMessage(language))
                    .build();
            resp.setConversationId(conversationId);
            return resp;
        }

        // Bước 2: Tải lịch sử cuộc hội thoại
        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        // Bước 3: Thử dùng Gemini để hiểu ngữ cảnh phức tạp
        GeminiParseResult geminiResult = null;
        try {
            geminiResult = geminiClient.parse(message, request.getBase64Image(), history, language);
        } catch (Exception e) {
            log.warn("Gemini parse thất bại, chuyển sang sử dụng quy tắc (rules): {}", e.getMessage());
        }

        // Bước 4: Xác định ý định (kết hợp: Gemini + dựa trên quy tắc)
        IntentResult intentResult;
        if (geminiResult != null && geminiResult.intent != null
                && !geminiResult.intent.isBlank()
                && !"UNKNOWN".equalsIgnoreCase(geminiResult.intent)) {
            // Gemini thành công — sử dụng ý định từ Gemini
            intentResult = mapGeminiIntent(geminiResult.intent);
        } else if (geminiResult != null && geminiResult.entries != null && !geminiResult.entries.isEmpty()) {
            // Gemini tìm thấy các giao dịch (thường từ hình ảnh) nhưng không gán nhãn ý định rõ ràng
            intentResult = IntentResult.builder()
                    .intent(Intent.INSERT_TRANSACTION)
                    .confidence(0.8)
                    .source(IntentResult.Source.GEMINI)
                    .build();
        } else {
            // Dự phòng dựa trên quy tắc (rules)
            intentResult = intentDetector.detect(parsed);
        }

        // ── DỰ PHÒNG LOẠI DANH MỤC (Nếu ý định mơ hồ hoặc không xác định) ──
        if (intentResult.getIntent() == Intent.UNKNOWN || intentResult.getIntent() == Intent.INSERT_TRANSACTION) {
             String norm = parsed.getNormalizedText();
             // Tìm bằng chứng cho thấy đây là một kế hoạch (tháng, "cho", "đặt", "tạo")
             boolean isPlanContext = norm.contains("thang") || norm.contains("month") 
                                  || norm.contains("cho ") || norm.contains("vao ")
                                  || norm.contains("dat ") || norm.contains("tao ") || norm.contains("han muc");
             
             if (isPlanContext) {
                 String inferred = entityExtractor.inferCategory(norm);
                 if (inferred != null) {
                     Map<String, Long> nameToId = getNameToIdMap();
                     Long catId = resolveCategoryId(nameToId, entityExtractor.normalizeCategoryName(inferred), null);
                     if (catId != null) {
                         EntryType type = categoryRepository.findById(catId).map(Category::getType).orElse(null);
                         if (type == EntryType.EXPENSE) {
                             intentResult = IntentResult.builder().intent(Intent.CREATE_BUDGET).confidence(0.7).build();
                         } else if (type == EntryType.INCOME) {
                             intentResult = IntentResult.builder().intent(Intent.CREATE_INCOME_GOAL).confidence(0.7).build();
                         }
                     }
                 }
             }
        }

        // ── Hỗ trợ các tin nhắn tiếp tục ──
        PendingPlanAction pending = planningState.get(conversationId);
        if (pending != null) {
            String norm = parsed.getNormalizedText();
            boolean isCancel = norm.contains("hủy") || norm.contains("bo qua") || norm.contains("lam lai") || norm.contains("cancel");
            if (isCancel) {
                planningState.remove(conversationId);
                return finalizeResponse(AiAssistantResponse.builder()
                        .intent("DELETE").reply(responseGenerator.t(language, "Đã hủy thao tác.", "Operation cancelled.")).build(),
                        conversationId, message, request.getBase64Image(), request.getUserId());
            }

            // Xử lý mọi thứ khác dưới dạng tiếp tục của ý định đang chờ xử lý
            Intent forcedIntent = "CREATE_BUDGET".equals(pending.intent) ? Intent.CREATE_BUDGET : Intent.CREATE_INCOME_GOAL;
            intentResult = IntentResult.builder().intent(forcedIntent).confidence(1.0).source(IntentResult.Source.RULE).build();

            // Phát hiện các từ khóa sửa đổi (đổi, sai, khác, v.v.)
            boolean isCorrection = norm.contains("doi ") || norm.contains("sai ") 
                                || norm.contains("nham") || norm.contains("khac") 
                                || norm.contains("thay ") || norm.contains("sua ");
            if (isCorrection) {
                if (norm.contains("thang") || norm.contains("ngay") || norm.contains("month")) pending.month = null;
                if (norm.contains("tien") || norm.contains("so tien") || norm.contains("amount")) pending.amount = null;
                if (norm.contains("hang muc") || norm.contains("danh muc") || norm.contains("loai") || norm.contains("khac") || norm.contains("category")) pending.category = null;
            }
            
            // Cố gắng trích xuất bất kỳ thông tin mới nào (tháng, số tiền, danh mục)
            if (geminiResult != null && geminiResult.entries != null && !geminiResult.entries.isEmpty()) {
                GeminiParsedEntry e = geminiResult.entries.get(0);
                if (e.amount != null) pending.amount = e.amount;
                if (e.categoryName != null) pending.category = e.categoryName;
                if (e.date != null) pending.month = e.date;
            }
            // Trích xuất tháng thủ công nếu có thể
            if (pending.month == null && (message.toLowerCase().contains("tháng") || message.toLowerCase().contains("month") || message.matches(".*\\b(t1|t2|t3|t4|t5|t6|t7|t8|t9|t10|t11|t12)\\b.*"))) {
                pending.month = message; // Chỉ lưu chuỗi thô cho logic parseDate
            }
            if (pending.category == null && parsed.getNormalizedText().length() > 0 && !parsed.hasAmounts() && !isRuleBasedConfirmation(parsed.getNormalizedText())) {
                 // Giả định văn bản có thể là một danh mục nếu nó không phải là xác nhận và không có số tiền
                 if (!message.toLowerCase().contains("tháng") && !message.toLowerCase().contains("month")) {
                     pending.category = message;
                 }
            }
        }

        // Bước 5: Điều phối theo ý định
        AiAssistantResponse response;
        switch (intentResult.getIntent()) {
            case INSERT_TRANSACTION:
                response = handleInsert(message, parsed, geminiResult, request.getAccountId(),
                        request.getUserId(), language, history);
                break;
            case QUERY_TRANSACTION:
                response = handleQuery(parsed, geminiResult, request.getUserId(), language);
                break;
            case UPDATE_TRANSACTION:
                response = handleUpdate(message, parsed, geminiResult, request.getAccountId(), language);
                break;
            case DELETE_TRANSACTION:
                response = handleDelete(message, parsed, geminiResult, language);
                break;
            case VIEW_FINANCIAL_PLAN:
            case BUDGET_QUERY:
                response = handleBudgetQuery(request.getUserId(), language);
                break;
            case MONTHLY_SUMMARY:
                response = handleMonthlySummary(request.getUserId(), language);
                break;
            case FINANCIAL_SCORE:
                response = handleFinancialScore(request.getUserId(), language);
                break;
            case CREATE_BUDGET:
                response = handleCreateBudget(parsed, geminiResult, request.getUserId(), language, conversationId);
                break;
            case CREATE_INCOME_GOAL:
                response = handleCreateIncomeGoal(parsed, geminiResult, request.getUserId(), language, conversationId);
                break;
            case FINANCIAL_ADVICE:
            case GENERAL_CHAT:
                response = handleAdvice(geminiResult, request.getUserId(), language);
                break;
            default:
                // Nhận biết ngữ cảnh: kiểm tra xem đây có phải là phản hồi cho một yêu cầu làm rõ không
                response = handleContextualFallback(parsed, geminiResult, history, request, language);
                break;
        }

        return finalizeResponse(response, conversationId, message, request.getBase64Image(), request.getUserId());
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ THÊM GIAO DỊCH
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleInsert(String originalMessage, ParsedMessage parsed,
            GeminiParseResult gemini,
            Long forcedAccountId, Long userId, String language, List<AiMessage> history) {
        // Trích xuất các ô (slots) giao dịch từ Gemini hoặc quy tắc quy định
        List<TransactionSlot> slots;
        if (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) {
            slots = gemini.entries.stream().map(e -> TransactionSlot.builder()
                    .amount(e.amount)
                    .categoryName(e.categoryName)
                    .note(e.note)
                    .type(e.type)
                    .date(e.date)
                    .build()).collect(Collectors.toList());
        } else {
            slots = entityExtractor.extractTransactionSlots(parsed);
        }

        // ── TRÍCH XUẤT VÀ HỢP NHẤT BẢN NHÁP ──
        List<GeminiParsedEntry> draftEntries = gemini != null && gemini.entries != null && !gemini.entries.isEmpty()
                ? new ArrayList<>(gemini.entries)
                : (slots.isEmpty() ? new ArrayList<>() : slots.stream().map(s -> {
                    GeminiParsedEntry e = new GeminiParsedEntry();
                    e.amount = s.getAmount();
                    e.categoryName = s.getCategoryName();
                    e.note = s.getNote();
                    e.type = s.getType();
                    e.date = s.getDate();
                    return e;
                }).collect(Collectors.toList()));

        // Khôi phục các bản nháp trước đó từ lịch sử để đảm bảo tích lũy qua các lượt hội thoại
        List<GeminiParsedEntry> previousDrafts = extractDraftEntriesFromHistory(history);
        if (!previousDrafts.isEmpty()) {
            for (GeminiParsedEntry prev : previousDrafts) {
                boolean duplicate = draftEntries.stream().anyMatch(curr -> isSameEntry(curr, prev));
                if (!duplicate) {
                    draftEntries.add(0, prev); // Thêm vào đầu để duy trì thứ tự
                }
            }
        }

        // ── QUYẾT ĐỊNH Ý ĐỊNH ──
        boolean isConfirmation = (gemini != null && Boolean.TRUE.equals(gemini.isConfirmation))
                || isRuleBasedConfirmation(parsed.getNormalizedText());

        if (draftEntries.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply(responseGenerator.insertEmpty(language))
                    .build();
        }

        // Nếu KHÔNG xác nhận, hiển thị bản nháp
        if (!isConfirmation) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .isDraft(true)
                    .entries(draftEntries)
                    .reply(responseGenerator.draftMessage(draftEntries, language))
                    .build();
        }

        // Nếu XÁC NHẬN, tiến hành lưu (bên dưới)

        // ── LUỒNG CÔNG VIỆC LƯU (CHỈ KHI XÁC NHẬN) ──
        // Nếu KHÔNG phải lượt xác nhận, chúng ta CÓ THỂ vẫn cần làm rõ cho các ô (slots) MỚI
        if (!isConfirmation && !slots.isEmpty()) {
            slots = contextManager.resolveWithContext(slots,
                    IntentResult.builder().intent(Intent.INSERT_TRANSACTION).build(), parsed, history);

            String clarification = contextManager.detectClarificationNeeded(slots,
                    IntentResult.builder().intent(Intent.INSERT_TRANSACTION).build(), language);
            if (clarification != null) {
                return AiAssistantResponse.builder()
                        .intent("INSERT")
                        .refreshRequired(false)
                        .reply(clarification)
                        .build();
            }
        }

        // Xác định tài khoản/ví
        AccountResolution acctRes = resolveAccount(originalMessage, forcedAccountId, userId, language);
        if (acctRes.errorMessage != null) {
            return AiAssistantResponse.builder().intent("INSERT").reply(acctRes.errorMessage).build();
        }
        if (acctRes.needsSelection) {
            return AiAssistantResponse.builder()
                    .intent("NEED_ACCOUNT")
                    .needsAccountSelection(true)
                    .reply(responseGenerator.needAccountSelection(language))
                    .build();
        }
        if (acctRes.accountId == null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply(responseGenerator.noAccount(language))
                    .build();
        }

        Long accountId = acctRes.accountId;
        Long effectiveUserId = userId;
        if (effectiveUserId == null && accountId != null) {
            effectiveUserId = accountRepository.findById(accountId)
                    .map(Account::getUserId).orElse(null);
        }

        Map<String, Long> nameToId = getNameToIdMap();
        Long fallbackCategoryId = resolveFallbackCategoryId(nameToId);
        if (fallbackCategoryId == null) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .reply(responseGenerator.noCategories(language))
                    .build();
        }
        Long incomeCategoryId = resolveCategoryId(nameToId, "Nạp tiền", fallbackCategoryId);

        List<String> savedLines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int createdCount = 0;

        for (GeminiParsedEntry slot : draftEntries) {
            if (slot == null || slot.getAmount() == null || slot.getAmount().compareTo(BigDecimal.ZERO) <= 0)
                continue;

            String type = slot.getType() == null || slot.getType().isBlank()
                    ? "EXPENSE"
                    : slot.getType().toUpperCase(Locale.ROOT);
            if (!"EXPENSE".equals(type) && !"INCOME".equals(type))
                type = "EXPENSE";

            String resolvedCategory = entityExtractor.normalizeCategoryName(slot.getCategoryName());
            if ("INCOME".equals(type))
                resolvedCategory = "Nạp tiền";
            else if (resolvedCategory == null || resolvedCategory.isBlank())
                resolvedCategory = null;

            Long preferredFallback = "INCOME".equals(type) ? incomeCategoryId : fallbackCategoryId;
            Long categoryId = resolveCategoryId(nameToId, resolvedCategory, preferredFallback);
            LocalDate date = parseDate(slot.getDate(), LocalDate.now());
            String note = slot.getNote() != null && !slot.getNote().isBlank()
                    ? slot.getNote().trim()
                    : originalMessage;
            if (note.length() > 2000)
                note = note.substring(0, 1997) + "...";

            CreateEntryRequest req = CreateEntryRequest.builder()
                    .amount(slot.getAmount())
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
                savedLines.add(String.format("%s %s • %s", sign,
                        responseGenerator.formatVnd(slot.getAmount(), language), catName));
            } catch (Exception ex) {
                log.error("Failed to create entry from AI parse: {}", req, ex);
                errors.add(ex.getMessage() != null ? ex.getMessage() : "Unknown error");
            }
        }

        if (createdCount == 0) {
            String errorText = errors.isEmpty()
                    ? responseGenerator.insertFailed(null, language)
                    : responseGenerator.insertFailed(errors.get(0), language);
            return AiAssistantResponse.builder()
                    .intent("INSERT").refreshRequired(false).reply(errorText).build();
        }

        return AiAssistantResponse.builder()
                .intent("INSERT")
                .createdCount(createdCount)
                .refreshRequired(true)
                .reply(responseGenerator.insertSuccess(createdCount, savedLines, language))
                .build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ TRUY VẤN (ủy thác cho SpendingAnalyticsService)
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleQuery(ParsedMessage parsed, GeminiParseResult gemini, Long userId,
            String language) {
        LocalDate today = LocalDate.now();
        LocalDate start, end;
        String typeFilter, metric;
        String categoryNameFilter = null;
        Integer limit = null;

        if (gemini != null && gemini.query != null) {
            start = parseDate(gemini.query.startDate, today.withDayOfMonth(1));
            end = parseDate(gemini.query.endDate, today);
            typeFilter = gemini.query.type != null ? gemini.query.type.toUpperCase(Locale.ROOT) : "EXPENSE";
            metric = gemini.query.metric != null ? gemini.query.metric.toUpperCase(Locale.ROOT) : "TOTAL";
            categoryNameFilter = gemini.query.categoryName;
            limit = gemini.query.limit;
        } else {
            start = parsed.getStartDate() != null ? parsed.getStartDate() : today.withDayOfMonth(1);
            end = parsed.getEndDate() != null ? parsed.getEndDate() : today;
            String normalized = parsed.getNormalizedText();
            typeFilter = entityExtractor.detectQueryType(normalized);
            metric = entityExtractor.detectMetric(normalized);
        }

        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        if (!List.of("EXPENSE", "INCOME", "TRANSFER", "ALL").contains(typeFilter))
            typeFilter = "EXPENSE";

        // Sử dụng SpendingAnalyticsService để phân tích dữ liệu
        switch (metric) {
            case "TOP_CATEGORY":
                return buildTopCategoryReply(start, end, typeFilter, language);
            case "LIST":
                return buildListReply(start, end, typeFilter, limit, categoryNameFilter, language);
            case "AVERAGE":
                return buildAverageReply(start, end, typeFilter, language);
            case "TREND":
                return buildTrendReply(start, end, typeFilter, language);
            case "PERCENTAGE":
                return buildPercentageReply(start, end, typeFilter, language);
            case "BUDGET":
                return handleBudgetQuery(userId, language);
            case "MONTHLY_SUMMARY":
                return handleMonthlySummary(userId, language);
            case "FINANCIAL_HEALTH":
                return buildFinancialHealthReply(userId, language);
            case "WEEKLY_PATTERN":
                return buildWeeklyPatternReply(userId, start, end, language);
            case "SMART_SUGGESTION":
                return buildSmartSuggestionReply(userId, language);
            case "FINANCIAL_SCORE":
                return handleFinancialScore(userId, language);
            case "TOTAL":
            default:
                return buildTotalReply(start, end, typeFilter, language);
        }
    }

    private AiAssistantResponse buildTotalReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        BigDecimal total = analyticsService.getTotalSpending(start, end, typeFilter);
        String label;
        if ("ALL".equals(typeFilter))
            label = responseGenerator.t(language, "Tổng giao dịch", "Total transactions");
        else if ("INCOME".equals(typeFilter))
            label = responseGenerator.t(language, "Tổng thu", "Total income");
        else
            label = responseGenerator.t(language, "Tổng chi", "Total expense");
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.totalReply(start, end, total, label, language))
                .build();
    }

    private AiAssistantResponse buildTopCategoryReply(LocalDate start, LocalDate end, String typeFilter,
            String language) {
        List<CategoryTotal> top = analyticsService.getTopCategories(start, end, typeFilter, 1);
        if (top.isEmpty()) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.noDataForTopCategory(language)).build();
        }
        CategoryTotal first = top.get(0);
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.topCategoryReply(start, end, first.getCategoryName(), first.getTotal(),
                        language))
                .build();
    }

    private AiAssistantResponse buildAverageReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        DailyAverageResult avg = analyticsService.getDailyAverage(start, end, typeFilter);
        String verb = "INCOME".equals(typeFilter)
                ? responseGenerator.t(language, "thu", "income")
                : responseGenerator.t(language, "chi", "spending");
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.averageReply(start, end, avg.getAverage(), avg.getDays(), verb, language))
                .build();
    }

    private AiAssistantResponse buildTrendReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        TrendResult trend = analyticsService.getSpendingTrend(start, end, typeFilter);
        String label = "ALL".equals(typeFilter)
                ? responseGenerator.t(language, "giao dịch", "transactions")
                : ("INCOME".equals(typeFilter)
                        ? responseGenerator.t(language, "thu", "income")
                        : responseGenerator.t(language, "chi", "expense"));

        String trendText;
        switch (trend.getTrend()) {
            case "UP":
                trendText = responseGenerator.t(language,
                        "tăng " + trend.getPercentChange() + "%",
                        "up " + trend.getPercentChange() + "%");
                break;
            case "DOWN":
                trendText = responseGenerator.t(language,
                        "giảm " + trend.getPercentChange().abs() + "%",
                        "down " + trend.getPercentChange().abs() + "%");
                break;
            case "NEW":
                trendText = responseGenerator.t(language, "mới phát sinh", "new activity");
                break;
            default:
                trendText = responseGenerator.t(language, "không đổi", "no change");
                break;
        }

        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.trendReply(trend.getPreviousStart(), trend.getPreviousEnd(),
                        label, trendText, trend.getCurrentTotal(), trend.getPreviousTotal(), language))
                .build();
    }

    private AiAssistantResponse buildPercentageReply(LocalDate start, LocalDate end, String typeFilter,
            String language) {
        PercentageBreakdownResult result = analyticsService.getSpendingByCategory(start, end, typeFilter);
        if (result.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.noDataForPercentage(language)).build();
        }
        List<Map.Entry<String, BigDecimal>> breakdown = result.getBreakdowns().stream()
                .map(cp -> Map.entry(cp.getCategoryName(), cp.getAmount()))
                .collect(Collectors.toList());
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.percentageReply(start, end, breakdown, result.getTotal(), language))
                .build();
    }

    private AiAssistantResponse buildListReply(LocalDate start, LocalDate end, String typeFilter,
            Integer limit, String categoryNameFilter, String language) {
        int max = (limit == null || limit <= 0) ? 10 : Math.min(limit, 20);
        List<FinancialEntry> entries = analyticsService.getRecentTransactions(start, end, typeFilter, max);

        if (categoryNameFilter != null && !categoryNameFilter.isBlank()) {
            String normCat = entityExtractor.normalizeCategoryName(categoryNameFilter);
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, normCat, null);
            if (catId != null) {
                entries = entries.stream().filter(e -> Objects.equals(e.getCategoryId(), catId))
                        .collect(Collectors.toList());
            }
        }

        if (entries.isEmpty()) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.noTransactions(language)).build();
        }

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        StringBuilder sb = new StringBuilder();
        sb.append(responseGenerator.isEnglish(language)
                ? String.format("Transactions from %s to %s:\n", start.format(DATE_FMT), end.format(DATE_FMT))
                : String.format("Danh sách giao dịch từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));
        for (var e : entries) {
            String catName = idToName.getOrDefault(e.getCategoryId(), "Khác");
            String note = e.getNote() != null ? e.getNote() : "";
            sb.append(String.format("- %s • %s", responseGenerator.formatVnd(e.getAmount(), language), catName));
            if (!note.isBlank())
                sb.append(" (").append(responseGenerator.trimNote(note)).append(")");
            sb.append("\n");
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(sb.toString().trim()).build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ CẬP NHẬT
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleUpdate(String originalMessage, ParsedMessage parsed,
            GeminiParseResult gemini, Long forcedAccountId, String language) {
        List<FinancialEntry> targets;
        GeminiParsedTarget target = gemini != null ? gemini.target : null;

        if (target != null) {
            targets = findTargetEntries(target);
        } else {
            // Phát hiện mục tiêu dựa trên quy tắc (rules)
            targets = findTargetEntriesFromText(parsed);
        }

        if (targets.isEmpty()) {
            return AiAssistantResponse.builder().intent("UPDATE")
                    .reply(responseGenerator.updateNotFound(language)).build();
        }
        if (targets.size() > 1) {
            return AiAssistantResponse.builder().intent("UPDATE")
                    .reply(responseGenerator.updateMultipleMatches(language)).build();
        }

        FinancialEntry entry = targets.get(0);

        // Lấy dữ liệu mới
        GeminiParsedEntry newData = (gemini != null && gemini.entries != null && !gemini.entries.isEmpty())
                ? gemini.entries.get(0)
                : null;

        if (newData == null) {
            // Thử cứu vãn từ mẫu tin nhắn "thành/sang/đến"
            newData = salvageUpdateData(originalMessage);
        }

        if (newData == null) {
            return AiAssistantResponse.builder().intent("UPDATE")
                    .reply(responseGenerator.updateWhatToChange(language)).build();
        }

        if (newData.amount != null)
            entry.setAmount(newData.amount);
        if (newData.note != null && !newData.note.isBlank())
            entry.setNote(newData.note);
        if (newData.date != null && !newData.date.isBlank()) {
            LocalDate d = parseDate(newData.date, null);
            if (d != null)
                entry.setTransactionDate(d);
        }
        if (newData.categoryName != null && !newData.categoryName.isBlank()) {
            String normCat = entityExtractor.normalizeCategoryName(newData.categoryName);
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, normCat, entry.getCategoryId());
            entry.setCategoryId(catId);
        }

        entryRepository.save(entry);
        return AiAssistantResponse.builder()
                .intent("UPDATE").refreshRequired(true)
                .reply(responseGenerator.updateSuccess(entry.getAmount(), entry.getNote(), language))
                .build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ XÓA
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleDelete(String originalMessage, ParsedMessage parsed,
            GeminiParseResult gemini, String language) {
        List<FinancialEntry> targets;
        GeminiParsedTarget target = gemini != null ? gemini.target : null;
        boolean deleteAll = target != null && Boolean.TRUE.equals(target.deleteAll);

        if (target != null) {
            targets = findTargetEntries(target);
        } else {
            targets = findTargetEntriesFromText(parsed);
        }

        // Lớp bảo vệ an toàn: ngay cả khi mục tiêu từ Gemini tồn tại, hãy kiểm tra các từ khóa "xóa tất cả" trong tin nhắn
        String normalized = parsed.getNormalizedText();
        if (!deleteAll) {
            deleteAll = normalized.contains("tat ca") || normalized.contains("toan bo")
                    || normalized.contains("het") || normalized.contains("xoa het")
                    || normalized.contains("all") || normalized.contains("everything")
                    || normalized.contains("remove all") || normalized.contains("delete all");
        }

        // Trường hợp đặc biệt: "hủy bản nháp" thay vì "xóa khỏi DB"
        if (targets.isEmpty() && (normalized.contains("huy") || normalized.contains("lam lai") || normalized.contains("cancel") || normalized.contains("discard"))) {
            // Kiểm tra xem có bản nháp nào trong lịch sử không
            return AiAssistantResponse.builder()
                    .intent("DELETE")
                    .reply(responseGenerator.t(language, "Đã hủy các giao dịch đang chờ.", "Pending transactions discarded."))
                    .build();
        }

        if (targets.isEmpty()) {
            return AiAssistantResponse.builder().intent("DELETE")
                    .reply(responseGenerator.deleteNotFound(language)).build();
        }
        if (!deleteAll && targets.size() > 1) {
            return AiAssistantResponse.builder().intent("DELETE")
                    .reply(responseGenerator.deleteMultipleMatches(language)).build();
        }

        int deletedCount = targets.size();
        String detail = deletedCount == 1
                ? responseGenerator.formatVnd(targets.get(0).getAmount(), language) + " - " + targets.get(0).getNote()
                : null;
        entryRepository.deleteAll(targets);

        return AiAssistantResponse.builder()
                .intent("DELETE").refreshRequired(true)
                .reply(responseGenerator.deleteSuccess(deletedCount, detail, language))
                .build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ LỜI KHUYÊN
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleAdvice(GeminiParseResult gemini, Long userId, String language) {
        String reply = (gemini != null && gemini.adviceReply != null && !gemini.adviceReply.isBlank())
                ? gemini.adviceReply
                : responseGenerator.t(language,
                        "Mình có thể giúp bạn quản lý chi tiêu, ghi nhận thu chi, và phân tích tài chính. Hãy thử hỏi nhé!",
                        "I can help you manage expenses, record transactions, and analyze finances. Just ask!");

        // Làm phong phú thêm với ngữ cảnh tài chính nếu có userId
        if (userId != null) {
            try {
                LocalDate now = LocalDate.now();
                FinancialScoreResult score = financialScoreEngine.computeScore(userId, now.getMonthValue(),
                        now.getYear());
                String scoreContext = responseGenerator.t(language,
                        "\n\n📊 Điểm tài chính hiện tại: " + score.getTotalScore() + "/100 (Hạng " + score.getGrade()
                                + ")",
                        "\n\n📊 Current Financial Score: " + score.getTotalScore() + "/100 (Grade " + score.getGrade()
                                + ")");
                reply += scoreContext;
            } catch (Exception e) {
                log.debug("Could not compute financial score for advice context: {}", e.getMessage());
            }
        }

        return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ TRUY VẤN NGÂN SÁCH
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleBudgetQuery(Long userId, String language) {
        if (userId == null) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.noBudgetData(language)).build();
        }
        List<BudgetStatusResult> statuses = analyticsService.getAllBudgetStatuses(userId, LocalDate.now());
        if (statuses.isEmpty()) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.noBudgetData(language)).build();
        }
        return AiAssistantResponse.builder().intent("QUERY")
                .reply(responseGenerator.allBudgetStatusReply(statuses, language)).build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ TỔNG KẾT THÁNG
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleMonthlySummary(Long userId, String language) {
        LocalDate now = LocalDate.now();
        MonthlySummaryResult summary = analyticsService.getMonthlySummary(userId, now.getMonthValue(), now.getYear());
        // Thêm cảnh báo chi tiêu quá mức
        List<OverspendingAlert> alerts = analyticsService.getOverspendingAlerts(
                userId, now.withDayOfMonth(1), now);
        String reply = responseGenerator.monthlySummaryReply(summary, now.getMonthValue(), now.getYear(), language);
        if (!alerts.isEmpty()) {
            reply += "\n\n" + responseGenerator.overspendingAlertReply(alerts, language);
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(reply).build();
    }

    // ═════════════════════════════════════════════════════════
    // XỬ LÝ ĐIỂM TÀI CHÍNH
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleFinancialScore(Long userId, String language) {
        if (userId == null) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.t(language,
                            "Cần đăng nhập để xem điểm tài chính.",
                            "Please log in to see your financial score."))
                    .build();
        }
        LocalDate now = LocalDate.now();
        FinancialScoreResult score = financialScoreEngine.computeScore(userId, now.getMonthValue(), now.getYear());
        return AiAssistantResponse.builder().intent("QUERY")
                .reply(responseGenerator.financialScoreReply(score, language)).build();
    }

    // ═════════════════════════════════════════════════════════
    // CÁC BỘ XÂY DỰNG TRUY VẤN NÂNG CAO
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse buildFinancialHealthReply(Long userId, String language) {
        LocalDate now = LocalDate.now();
        FinancialHealthResult health = analyticsService.getFinancialHealth(userId, now.getMonthValue(), now.getYear());
        return AiAssistantResponse.builder().intent("QUERY")
                .reply(responseGenerator.financialHealthReply(health, language)).build();
    }

    private AiAssistantResponse buildWeeklyPatternReply(Long userId, LocalDate start, LocalDate end, String language) {
        WeeklyPatternResult pattern = analyticsService.getWeeklyPatterns(userId, start, end);
        return AiAssistantResponse.builder().intent("QUERY")
                .reply(responseGenerator.weeklyPatternReply(pattern, language)).build();
    }

    private AiAssistantResponse buildSmartSuggestionReply(Long userId, String language) {
        List<SmartSuggestionResult> suggestions = analyticsService.getSmartSuggestions(userId);
        return AiAssistantResponse.builder().intent("QUERY")
                .reply(responseGenerator.smartSuggestionReply(suggestions, language)).build();
    }

    // ═════════════════════════════════════════════════════════
    // DỰ PHÒNG THEO NGỮ CẢNH (xử lý các tin nhắn tiếp theo)
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleContextualFallback(ParsedMessage parsed, GeminiParseResult gemini,
            List<AiMessage> history,
            AiAssistantRequest request, String language) {
        // 1. Nếu Gemini thực sự tìm thấy các giao dịch ngay cả khi ý định KHÔNG XÁC ĐỊNH, hãy thử xử lý handleInsert
        if (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) {
            return handleInsert(parsed.getOriginalText(), parsed, gemini,
                    request.getAccountId(), request.getUserId(), language, history);
        }

        // 2. Thử điền các ô (slots) từ ngữ cảnh hội thoại (dựa trên văn bản)
        List<TransactionSlot> slots = entityExtractor.extractTransactionSlots(parsed);
        slots = contextManager.resolveWithContext(slots,
                IntentResult.builder().intent(Intent.INSERT_TRANSACTION).build(), parsed, history);

        // Nếu ngữ cảnh đã giải quyết thành một giao dịch hoàn chỉnh, hãy thêm nó
        boolean hasComplete = slots.stream().anyMatch(TransactionSlot::isComplete);
        if (hasComplete && parsed.hasAmounts()) {
            return handleInsert(parsed.getOriginalText(), parsed, null,
                    request.getAccountId(), request.getUserId(), language, history);
        }

        // 3. Yêu cầu làm rõ nếu chúng ta có thông tin một phần
        String clarification = contextManager.detectClarificationNeeded(slots,
                IntentResult.builder().intent(Intent.INSERT_TRANSACTION).build(), language);
        if (clarification != null && (slots.stream().anyMatch(s -> s.getNote() != null && !s.getNote().isBlank())
                || parsed.hasAmounts())) {
            return AiAssistantResponse.builder()
                    .intent("INSERT").refreshRequired(false).reply(clarification).build();
        }

        // 4. Dự phòng cuối cùng
        return AiAssistantResponse.builder()
                .intent("UNKNOWN")
                .reply(responseGenerator.unknownMessage(language))
                .build();
    }

    // ═════════════════════════════════════════════════════════
    // TÌM KIẾM GIAO DỊCH MỤC TIÊU
    // ═════════════════════════════════════════════════════════

    private List<FinancialEntry> findTargetEntries(GeminiParsedTarget target) {
        if (target == null)
            return List.of();
        String normalizedCat = target.categoryName != null
                ? entityExtractor.normalizeCategoryName(target.categoryName)
                : null;
        LocalDate searchDate = parseDate(target.date, null);
        LocalDate start = searchDate != null ? searchDate : LocalDate.now().minusDays(30);
        LocalDate end = searchDate != null ? searchDate : LocalDate.now();

        List<FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream().filter(e -> {
            if (target.amount != null && e.getAmount().compareTo(target.amount) != 0)
                return false;
            if (normalizedCat != null && !normalizedCat.isBlank()) {
                String catName = categoryService.getIdToNameMap()
                        .getOrDefault(e.getCategoryId(), "").toLowerCase(Locale.ROOT);
                String normCatName = textPreprocessor.normalizeVietnamese(catName);
                String normTarget = textPreprocessor.normalizeVietnamese(normalizedCat.toLowerCase(Locale.ROOT));
                if (!normCatName.contains(normTarget) && !normTarget.contains(normCatName))
                    return false;
            }
            if (target.noteKeywords != null && !target.noteKeywords.isBlank()) {
                String note = e.getNote() != null
                        ? textPreprocessor.normalizeVietnamese(e.getNote().toLowerCase(Locale.ROOT))
                        : "";
                String keywords = cleanKeywords(
                        textPreprocessor.normalizeVietnamese(target.noteKeywords.toLowerCase(Locale.ROOT)));
                if (!keywords.isBlank() && !note.contains(keywords))
                    return false;
            }
            return true;
        }).collect(Collectors.toList());
    }

    private List<FinancialEntry> findTargetEntriesFromText(ParsedMessage parsed) {
        BigDecimal amount = parsed.getFirstAmount();
        LocalDate searchDate = textPreprocessor.detectSingleDate(parsed.getNormalizedText(), null);
        LocalDate start = searchDate != null ? searchDate : LocalDate.now().minusDays(30);
        LocalDate end = searchDate != null ? searchDate : LocalDate.now();

        List<FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream().filter(e -> {
            if (amount != null && e.getAmount().compareTo(amount) != 0)
                return false;
            return true;
        }).collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════
    // CÁC PHƯƠNG THỨC HỖ TRỢ
    // ═════════════════════════════════════════════════════════

    private IntentResult mapGeminiIntent(String geminiIntent) {
        String upper = geminiIntent.trim().toUpperCase(Locale.ROOT);
        Intent intent = switch (upper) {
            case "INSERT" -> Intent.INSERT_TRANSACTION;
            case "QUERY" -> Intent.QUERY_TRANSACTION;
            case "UPDATE" -> Intent.UPDATE_TRANSACTION;
            case "DELETE" -> Intent.DELETE_TRANSACTION;
            case "ADVICE" -> Intent.FINANCIAL_ADVICE;
            case "SET_BUDGET" -> Intent.CREATE_BUDGET;
            case "CREATE_BUDGET" -> Intent.CREATE_BUDGET;
            case "SET_INCOME_TARGET" -> Intent.CREATE_INCOME_GOAL;
            case "CREATE_INCOME_GOAL" -> Intent.CREATE_INCOME_GOAL;
            case "VIEW_FINANCIAL_PLAN" -> Intent.VIEW_FINANCIAL_PLAN;
            default -> Intent.UNKNOWN;
        };
        return IntentResult.builder().intent(intent).confidence(0.9)
                .source(IntentResult.Source.GEMINI).build();
    }

    private GeminiParsedEntry salvageUpdateData(String originalMessage) {
        String lower = originalMessage.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("\\b(thanh|thành|sang|thay|den|đến|to|into|as)\\b");
        if (parts.length >= 2) {
            GeminiParsedEntry entry = new GeminiParsedEntry();
            entry.amount = textPreprocessor.extractSingleAmount(parts[1]);
            String notePart = parts[1].replaceAll("[0-9.,dđkK]+", " ").trim();
            if (notePart.length() > 2)
                entry.note = parts[1].trim();
            String catMatch = entityExtractor.inferCategory(textPreprocessor.normalizeVietnamese(parts[1]));
            if (catMatch != null)
                entry.categoryName = catMatch;
            return entry;
        }
        return null;
    }

    private String cleanKeywords(String keywords) {
        return keywords
                .replaceAll(
                        "\\b(sua|doi|cap nhat|thanh|sang|thay|den|xoa|huy|bo|giup|giao dich|khoan|cai|nay|tat ca|het|hom nay|hom qua|ngay|thang|nam|vi|momo|tien|chi|tieu|vua|nay|chieu|sang|toi|update|change|edit|set|delete|remove|transaction|entry|this|that|all|everything|today|yesterday|day|month|year|wallet|money|expense|income|spend|spent|buy|payment|pay|recent|nap|nap tien|gui|rut|banking|transfer)\\b",
                        "")
                .replaceAll("[0-9.,dđkK]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private AccountResolution resolveAccount(String message, Long forcedAccountId, Long userId, String language) {
        List<Account> accounts = (userId != null)
                ? accountRepository.findByUserIdAndIsDeletedFalseOrderByNameAsc(userId)
                : accountRepository.findAll();
        if (accounts.isEmpty())
            return AccountResolution.none();
        if (forcedAccountId != null) {
            boolean exists = accounts.stream().anyMatch(a -> Objects.equals(a.getId(), forcedAccountId));
            if (exists)
                return AccountResolution.selected(forcedAccountId);
            return AccountResolution.error(responseGenerator.t(language,
                    "Ví/tài khoản không tồn tại.", "Wallet/account does not exist."));
        }
        if (accounts.size() == 1)
            return AccountResolution.selected(accounts.get(0).getId());
        // Thử khớp tên tài khoản trong tin nhắn
        if (message != null && !message.isBlank()) {
            String normMsg = textPreprocessor.normalizeVietnamese(message);
            Long bestId = null;
            int bestLen = 0;
            for (Account a : accounts) {
                String name = a.getName() == null ? "" : a.getName();
                String normName = textPreprocessor.normalizeVietnamese(name);
                if (!normName.isBlank() && normMsg.contains(normName) && normName.length() > bestLen) {
                    bestLen = normName.length();
                    bestId = a.getId();
                }
            }
            if (bestId != null)
                return AccountResolution.selected(bestId);
        }
        return AccountResolution.needsSelection();
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
        String normTarget = textPreprocessor.normalizeVietnamese(name.trim().toLowerCase(Locale.ROOT));
        for (var entry : nameToId.entrySet()) {
            if (textPreprocessor.normalizeVietnamese(entry.getKey()).equals(normTarget))
                return entry.getValue();
        }
        return fallback;
    }

    private Long resolveFallbackCategoryId(Map<String, Long> nameToId) {
        Long other = nameToId.get("khác");
        return other != null ? other : nameToId.values().stream().findFirst().orElse(null);
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

    private AiAssistantResponse finalizeResponse(AiAssistantResponse response, String conversationId,
            String message, String base64Image, Long userId) {
        response.setConversationId(conversationId);
        boolean hasImage = base64Image != null && !base64Image.isBlank();
        if ((message != null && !message.isBlank()) || hasImage) {
            String safeContent = (message == null || message.isBlank()) ? "[Hóa đơn]" : message.trim();
            String imageUrl = hasImage ? saveBase64Image(base64Image) : null;
            saveMessage(conversationId, "USER", safeContent, imageUrl, userId);
            if (response.getReply() != null) {
                saveMessage(conversationId, "ASSISTANT", response.getReply(), null, userId);
            }
        }
        return response;
    }

    public List<AiMessage> getHistory(Long userId) {
        if (userId == null)
            return List.of();
        // Load only the latest 50 messages to avoid large payloads
        List<AiMessage> all = aiMessageRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (all.size() > 50)
            return all.subList(all.size() - 50, all.size());
        return all;
    }

    @Transactional
    public void clearHistory(Long userId) {
        if (userId == null)
            return;
        aiMessageRepository.deleteByUserId(userId);
    }

    private void saveMessage(String conversationId, String role, String content, String imageUrl, Long userId) {
        String safe = content == null ? "" : content.trim();
        if (safe.length() > 4000)
            safe = safe.substring(0, 3997) + "...";
        aiMessageRepository.save(AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(role)
                .content(safe)
                .imageUrl(imageUrl)
                .build());
    }

    private String saveBase64Image(String base64Data) {
        if (base64Data == null || base64Data.isBlank())
            return null;
        try {
            // Xóa tiền tố nếu có (ví dụ: "data:image/png;base64,")
            String base64Part = base64Data;
            String extension = ".png"; // Default
            if (base64Data.contains(",")) {
                String header = base64Data.substring(0, base64Data.indexOf(","));
                base64Part = base64Data.substring(base64Data.indexOf(",") + 1);
                if (header.contains("image/jpeg"))
                    extension = ".jpg";
                else if (header.contains("image/gif"))
                    extension = ".gif";
                else if (header.contains("image/webp"))
                    extension = ".webp";
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64Part);
            String dir = "uploads/";
            java.io.File d = new java.io.File(dir);
            if (!d.exists())
                d.mkdirs();

            String filename = UUID.randomUUID().toString() + extension;
            Path path = Paths.get(dir + filename);
            Files.write(path, imageBytes);

            return "/" + dir + filename;
        } catch (Exception e) {
            log.error("Failed to save AI assistant image: {}", e.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════
    // THIẾT LẬP NGÂN SÁCH
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleCreateBudget(ParsedMessage parsed, GeminiParseResult gemini, Long userId,
            String language, String conversationId) {
        if (userId == null) {
            return AiAssistantResponse.builder().intent("ADVICE")
                    .reply(responseGenerator.t(language, "Bạn cần đăng nhập để đặt ngân sách.",
                            "Please log in to set a budget."))
                    .build();
        }

        GeminiParsedEntry data = (gemini != null && gemini.entries != null && !gemini.entries.isEmpty())
                ? gemini.entries.get(0)
                : null;
                
        // Đồng bộ trạng thái đang chờ xử lý
        PendingPlanAction pending = planningState.computeIfAbsent(conversationId, k -> {
            PendingPlanAction p = new PendingPlanAction();
            p.intent = "CREATE_BUDGET";
            return p;
        });

        if (data != null && data.amount != null && data.amount.compareTo(BigDecimal.ZERO) > 0) pending.amount = data.amount;
        if (data != null && data.categoryName != null) pending.category = data.categoryName;
        if (data != null && data.date != null) pending.month = data.date;

        // 1. Xác định Danh mục (Category)
        Map<String, Long> nameToId = getNameToIdMap();
        Long catId = null;
        if (pending.category != null) {
            catId = resolveCategoryId(nameToId, entityExtractor.normalizeCategoryName(pending.category), null);
        }
        if (catId == null) {
            String textToInfer = parsed.getNormalizedText()
                    .replaceAll("\\b(ngan sach|han muc|muc tieu|budget|goal|limit|luong|thu nhap)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            String inferred = entityExtractor.inferCategory(textToInfer);
            if (inferred != null) {
                catId = resolveCategoryId(nameToId, inferred, null);
            } else if ("CATEGORY".equals(pending.awaitingField)) {
                catId = resolveCategoryId(nameToId, entityExtractor.normalizeCategoryName(parsed.getNormalizedText()), null);
            }
        }

        if (catId == null) {
            pending.awaitingField = "CATEGORY";
            String reply = responseGenerator.t(language,
                    "Bạn muốn đặt ngân sách cho hạng mục nào?\n\nCác hạng mục hiện có:",
                    "Which category do you want to set a budget for?\n\nAvailable categories:");
            reply += getCategoryListResponse(EntryType.EXPENSE, language);
            reply += responseGenerator.t(language, "\n\nVí dụ: \"Tạo ngân sách [tên hạng mục] 3 triệu\"", "\n\nExample: \"Create budget [category] 3 million\"");
            return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
        }

        // Lưu tên danh mục đã xác định
        final Long finalCatId = catId;
        String resolvedName = nameToId.entrySet().stream()
                .filter(e -> e.getValue().equals(finalCatId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(pending.category != null ? pending.category : parsed.getOriginalText());
        pending.category = resolvedName;

        EntryType catType = categoryRepository.findById(catId)
                .map(Category::getType)
                .orElse(null);
        if (catType == EntryType.INCOME) {
            // Kiểm tra xem người dùng thực sự đề cập đến một danh mục thu nhập hay nó chỉ được suy luận từ một từ khóa trong tin nhắn kích hoạt
            boolean isExplicit = (parsed.getOriginalText().toLowerCase().contains(resolvedName.toLowerCase()));
            if (!isExplicit) {
                // Suy luận sai lệch tích cực (False positive): Tiến hành yêu cầu chọn danh mục như thể catId là null
                catId = null;
                pending.category = null;
                pending.awaitingField = "CATEGORY";
                String reply = responseGenerator.t(language,
                        "Bạn muốn đặt ngân sách cho hạng mục nào?\n\nCác hạng mục hiện có:",
                        "Which category do you want to set a budget for?\n\nAvailable categories:");
                reply += getCategoryListResponse(EntryType.EXPENSE, language);
                reply += responseGenerator.t(language, "\n\nVí dụ: \"Tạo ngân sách [tên hạng mục] 3 triệu\"", "\n\nExample: \"Create budget [category] 3 million\"");
                return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
            } else {
                pending.category = null; // Xóa để kích hoạt chọn lại
                pending.awaitingField = "CATEGORY";
                String reply = responseGenerator.t(language,
                        String.format("Hạng mục **'%s'** thuộc loại THU. Vì bạn đang đặt ngân sách chi tiêu, hãy chọn một danh mục CHI bên dưới:", resolvedName),
                        String.format("The category **'%s'** is an INCOME type. Since you are setting a spending budget, please choose an EXPENSE category below:", resolvedName));
                reply += getCategoryListResponse(EntryType.EXPENSE, language);
                return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
            }
        }

        // Thiếu số tiền (Amount)
        if (pending.amount == null || pending.amount.compareTo(BigDecimal.ZERO) <= 0) {
            // Thử trích xuất từ tin nhắn
            BigDecimal msgAmt = textPreprocessor.extractSingleAmount(parsed.getOriginalText());
            if (msgAmt != null) {
                pending.amount = msgAmt;
            } else {
                pending.awaitingField = "AMOUNT";
                String catName = categoryService.getIdToNameMap().get(catId);
                String reply = responseGenerator.t(language,
                        "Bạn muốn đặt ngân sách bao nhiêu cho " + catName + "?",
                        "How much budget do you want to set for " + catName + "?");
                return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
            }
        }

        // TRƯỜNG HỢP 2: Đã phát hiện Danh mục + Số tiền nhưng CHƯA có tháng -> hỏi tháng nào
        if (pending.month == null || pending.month.isBlank()) {
            if (parsed.getStartDate() != null) {
                pending.month = parsed.getStartDate().toString();
            } else {
                String lowerMessage = parsed.getOriginalText().toLowerCase();
                if (lowerMessage.contains("tháng") || lowerMessage.contains("month") 
                    || lowerMessage.matches(".*\\b(t1|t2|t3|t4|t5|t6|t7|t8|t9|t10|t11|t12)\\b.*")) {
                    pending.month = parsed.getOriginalText();
                } else {
                    pending.awaitingField = "MONTH";
                    String reply = responseGenerator.t(language,
                            "Bạn muốn đặt ngân sách này cho tháng nào?\n\nVí dụ:\ntháng này\ntháng 6\ntháng 12",
                            "Which month do you want to set this budget for?\n\nExamples:\nthis month\nmonth 6\nmonth 12");
                    return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
                }
            }
        }

        // Xác định các ngày từ dữ liệu được trích xuất hoặc ngày hiện tại nếu cần dự phòng sau khi phát hiện từ khóa
        LocalDate today = LocalDate.now();
        LocalDate parsedDate = parseDate(pending.month, today);
        LocalDate start = parsedDate.withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        
        String catName = categoryService.getIdToNameMap().get(catId);
        
        // Có phải là Tin nhắn Xác nhận không?
        boolean isConfirmation = (gemini != null && Boolean.TRUE.equals(gemini.isConfirmation)) || isRuleBasedConfirmation(parsed.getNormalizedText());
        if (!isConfirmation) {
            pending.awaitingField = "CONFIRMATION";
            // TRƯỜNG HỢP 1: Đề xuất tạo ngân sách
            String reply = responseGenerator.t(language,
                    String.format("Bạn muốn tạo ngân sách:\n\nTháng: %d/%d\nHạng mục: %s\nSố tiền: %s\n\nBạn có muốn lưu ngân sách này không?",
                            start.getMonthValue(), start.getYear(), catName, responseGenerator.formatVnd(pending.amount, language)),
                    String.format("You want to create a budget:\n\nMonth: %d/%d\nCategory: %s\nAmount: %s\n\nDo you want to save this budget?",
                            start.getMonthValue(), start.getYear(), catName, responseGenerator.formatVnd(pending.amount, language)));
            return AiAssistantResponse.builder().intent("CREATE_BUDGET").refreshRequired(false).reply(reply).build();
        }

        // Hành động đã được xác nhận: Lưu/Cập nhật ngân sách
        Optional<Budget> existing = budgetRepository
                .findFirstByUserIdAndCategoryIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        userId, catId, start, end);

        Budget budget = existing.orElse(new Budget());
        budget.setUserId(userId);
        budget.setCategoryId(catId);
        budget.setAmount(pending.amount);
        budget.setStartDate(start);
        budget.setEndDate(end);
        budgetRepository.save(budget);
        
        planningState.remove(conversationId);

        String reply = responseGenerator.t(language,
                String.format("Vâng, mình đã ghi nhận hạn mức ngân sách cho danh mục **%s** là **%s** cho tháng %d/%d.",
                        catName, responseGenerator.formatVnd(pending.amount, language), start.getMonthValue(),
                        start.getYear()),
                String.format("Okay, I've set a **%s** budget for **%s** for month %d/%d.",
                        responseGenerator.formatVnd(pending.amount, language), catName, start.getMonthValue(),
                        start.getYear()));

        return AiAssistantResponse.builder().intent("QUERY").refreshRequired(true).reply(reply).build();
    }

    // ═════════════════════════════════════════════════════════
    // THIẾT LẬP MỤC TIÊU THU NHẬP
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleCreateIncomeGoal(ParsedMessage parsed, GeminiParseResult gemini, Long userId,
            String language, String conversationId) {
        if (userId == null) {
            return AiAssistantResponse.builder().intent("ADVICE")
                    .reply(responseGenerator.t(language, "Bạn cần đăng nhập để đặt mục tiêu thu nhập.",
                            "Please log in to set an income target."))
                    .build();
        }

        GeminiParsedEntry data = (gemini != null && gemini.entries != null && !gemini.entries.isEmpty())
                ? gemini.entries.get(0)
                : null;

        // Sync pending state
        PendingPlanAction pending = planningState.computeIfAbsent(conversationId, k -> {
            PendingPlanAction p = new PendingPlanAction();
            p.intent = "CREATE_INCOME_GOAL";
            return p;
        });

        if (data != null && data.amount != null && data.amount.compareTo(BigDecimal.ZERO) > 0) pending.amount = data.amount;
        if (data != null && data.categoryName != null) pending.category = data.categoryName;
        if (data != null && data.date != null) pending.month = data.date;

        // 1. Resolve Category
        Map<String, Long> nameToId = getNameToIdMap();
        Long catId = null;
        if (pending.category != null) {
            catId = resolveCategoryId(nameToId, entityExtractor.normalizeCategoryName(pending.category), null);
        }
        if (catId == null) {
            String textToInfer = parsed.getNormalizedText()
                    .replaceAll("\\b(ngan sach|han muc|muc tieu|budget|goal|limit|luong|thu nhap)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            String inferred = entityExtractor.inferCategory(textToInfer);
            if (inferred != null) {
                catId = resolveCategoryId(nameToId, inferred, null);
            } else if ("CATEGORY".equals(pending.awaitingField)) {
                catId = resolveCategoryId(nameToId, entityExtractor.normalizeCategoryName(parsed.getNormalizedText()), null);
            }
        }

        if (catId == null) {
            pending.awaitingField = "CATEGORY";
            String reply = responseGenerator.t(language,
                    "Bạn muốn tạo mục tiêu thu nhập cho hạng mục nào?\n\nCác hạng mục hiện có:",
                    "Which category do you want to set an income target for?\n\nAvailable categories:");
            reply += getCategoryListResponse(EntryType.INCOME, language);
            reply += responseGenerator.t(language, "\n\nVí dụ: \"Mục tiêu [tên hạng mục] 10 triệu\"", "\n\nExample: \"Income goal [category] 10 million\"");
            return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
        }
        
        // Store resolved category name
        final Long finalCatId = catId;
        String resolvedName = nameToId.entrySet().stream()
                .filter(e -> e.getValue().equals(finalCatId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(pending.category != null ? pending.category : parsed.getOriginalText());
        pending.category = resolvedName;

        EntryType catType = categoryRepository.findById(catId)
                .map(Category::getType)
                .orElse(null);
        if (catType == EntryType.EXPENSE) {
            // Safety: check if it's a real mention
            boolean isExplicit = (parsed.getOriginalText().toLowerCase().contains(resolvedName.toLowerCase()));
            if (!isExplicit) {
                // False positive inference on trigger words like "tieu" in "muc tieu"
                catId = null;
                pending.category = null;
                pending.awaitingField = "CATEGORY";
                String reply = responseGenerator.t(language,
                        "Bạn muốn tạo mục tiêu thu nhập cho hạng mục nào?\n\nCác hạng mục hiện có:",
                        "Which category do you want to set an income target for?\n\nAvailable categories:");
                reply += getCategoryListResponse(EntryType.INCOME, language);
                reply += responseGenerator.t(language, "\n\nVí dụ: \"Mục tiêu [tên hạng mục] 10 triệu\"", "\n\nExample: \"Income goal [category] 10 million\"");
                return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
            } else {
                pending.category = null; // Clear to trigger re-selection
                pending.awaitingField = "CATEGORY";
                String reply = responseGenerator.t(language,
                        String.format("Hạng mục **'%s'** thuộc loại CHI. Vì bạn đang đặt mục tiêu thu nhập, hãy chọn một danh mục THU bên dưới:", resolvedName),
                        String.format("The category **'%s'** is an EXPENSE type. Since you are setting an income target, please choose an INCOME category below:", resolvedName));
                reply += getCategoryListResponse(EntryType.INCOME, language);
                return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
            }
        }

        // Thiếu số tiền (Amount)
        if (pending.amount == null || pending.amount.compareTo(BigDecimal.ZERO) <= 0) {
            // Attempt extraction from message
            BigDecimal msgAmt = textPreprocessor.extractSingleAmount(parsed.getOriginalText());
            if (msgAmt != null) {
                pending.amount = msgAmt;
            } else {
                pending.awaitingField = "AMOUNT";
                String catName = categoryService.getIdToNameMap().get(catId);
                String reply = responseGenerator.t(language,
                        "Bạn muốn đặt mục tiêu thu bao nhiêu cho " + catName + "?",
                        "How much income goal do you want to set for " + catName + "?");
                return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
            }
        }

        // TRƯỜNG HỢP 2: Đã phát hiện Danh mục + Số tiền nhưng CHƯA có tháng -> hỏi tháng nào
        if (pending.month == null || pending.month.isBlank()) {
            if (parsed.getStartDate() != null) {
                pending.month = parsed.getStartDate().toString();
            } else {
                String lowerMessage = parsed.getOriginalText().toLowerCase();
                if (lowerMessage.contains("tháng") || lowerMessage.contains("month") 
                    || lowerMessage.matches(".*\\b(t1|t2|t3|t4|t5|t6|t7|t8|t9|t10|t11|t12)\\b.*")) {
                    pending.month = parsed.getOriginalText();
                } else {
                    pending.awaitingField = "MONTH";
                    String reply = responseGenerator.t(language,
                            "Bạn muốn đặt mục tiêu này cho tháng nào?\n\nVí dụ:\ntháng này\ntháng 6\ntháng 12",
                            "Which month do you want to set this goal for?\n\nExamples:\nthis month\nmonth 6\nmonth 12");
                    return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
                }
            }
        }

        // Determine dates
        LocalDate today = LocalDate.now();
        LocalDate parsedDate = parseDate(pending.month, today);
        LocalDate start = parsedDate.withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        
        String catName = categoryService.getIdToNameMap().get(catId);

        // Có phải là Tin nhắn Xác nhận không?
        boolean isConfirmation = (gemini != null && Boolean.TRUE.equals(gemini.isConfirmation)) || isRuleBasedConfirmation(parsed.getNormalizedText());
        if (!isConfirmation) {
            pending.awaitingField = "CONFIRMATION";
            // CASE 1: Propose creating the goal
            String reply = responseGenerator.t(language,
                    String.format("Bạn muốn tạo mục tiêu thu:\n\nTháng: %d/%d\nHạng mục: %s\nSố tiền: %s\n\nBạn có muốn lưu mục tiêu này không?",
                            start.getMonthValue(), start.getYear(), catName, responseGenerator.formatVnd(pending.amount, language)),
                    String.format("You want to create an income goal:\n\nMonth: %d/%d\nCategory: %s\nAmount: %s\n\nDo you want to save this goal?",
                            start.getMonthValue(), start.getYear(), catName, responseGenerator.formatVnd(pending.amount, language)));
            return AiAssistantResponse.builder().intent("CREATE_INCOME_GOAL").refreshRequired(false).reply(reply).build();
        }

        // Action confirmed: Upsert goal
        Optional<Budget> existing = budgetRepository
                .findFirstByUserIdAndCategoryIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        userId, catId, start, end);

        Budget budget = existing.orElse(new Budget());
        budget.setUserId(userId);
        budget.setCategoryId(catId);
        budget.setAmount(pending.amount);
        budget.setStartDate(start);
        budget.setEndDate(end);
        budgetRepository.save(budget);
        
        planningState.remove(conversationId);

        String reply = responseGenerator.t(language,
                String.format("Vâng, mình đã ghi nhận mục tiêu thu nhập cho danh mục **%s** là **%s** cho tháng %d/%d.",
                        catName, responseGenerator.formatVnd(pending.amount, language), start.getMonthValue(),
                        start.getYear()),
                String.format("Okay, I've set a **%s** income target for **%s** for month %d/%d.",
                        responseGenerator.formatVnd(pending.amount, language), catName, start.getMonthValue(),
                        start.getYear()));

        return AiAssistantResponse.builder().intent("QUERY").refreshRequired(true).reply(reply).build();
    }

    private String getCategoryListResponse(EntryType type, String language) {
        List<Category> cats = categoryRepository.findByTypeOrderByNameAsc(type);
        if (cats.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder("\n\n");
        sb.append(type == EntryType.INCOME
                ? responseGenerator.t(language, "📌 Danh mục thu nhập:", "📌 Income categories:", "📌 収入カテゴリー:",
                        "📌 수입 카테고리:", "📌 收入类别:")
                : responseGenerator.t(language, "📌 Danh mục chi tiêu:", "📌 Expense categories:", "📌 支出カテゴリー:",
                        "📌 지출 카테고리:", "📌 支出类别:"));
        sb.append("\n");
        for (Category c : cats) {
            sb.append("- ").append(c.getName()).append("\n");
        }
        return sb.toString();
    }

    // ── CÁC PHƯƠNG THỨC HỖ TRỢ TRÍCH XUẤT NHÁP ──

    private List<GeminiParsedEntry> extractDraftEntriesFromHistory(List<AiMessage> history) {
        if (history == null || history.isEmpty())
            return new ArrayList<>();

        // Tìm tin nhắn cuối cùng của Assistant là bản nháp
        for (int i = history.size() - 1; i >= 0; i--) {
            AiMessage m = history.get(i);
            if ("ASSISTANT".equalsIgnoreCase(m.getRole())) {
                String content = m.getContent();
                if (content != null && (content.contains("kiểm tra lại") || content.contains("lưu giao dịch này không")
                        || content.contains("review") || content.contains("save this transaction"))) {
                    return parseDraftLines(content);
                }
            }
        }
        return new ArrayList<>();
    }

    private List<GeminiParsedEntry> parseDraftLines(String content) {
        List<GeminiParsedEntry> result = new ArrayList<>();
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("- ") || line.startsWith("+ ")) {
                GeminiParsedEntry entry = parseDraftLine(line);
                if (entry != null)
                    result.add(entry);
            }
        }
        return result;
    }

    private GeminiParsedEntry parseDraftLine(String line) {
        try {
            GeminiParsedEntry entry = new GeminiParsedEntry();
            entry.type = line.startsWith("+") ? "INCOME" : "EXPENSE";

            // Loại bỏ tiền tố (- hoặc +)
            String parts = line.substring(2).trim();

            // Tìm số tiền và danh mục: "30.000 đ • Ăn uống (Ăn phở)"
            int dotIdx = parts.indexOf("•");
            if (dotIdx == -1)
                return null;

            String amountPart = parts.substring(0, dotIdx).trim();
            String categoryAndNote = parts.substring(dotIdx + 1).trim();

            // Extract amount using textPreprocessor
            entry.amount = textPreprocessor.extractSingleAmount(amountPart);
            if (entry.amount == null)
                return null;

            // Extract category and note: "Ăn uống (Ăn phở)"
            int bracketIdx = categoryAndNote.indexOf("(");
            if (bracketIdx != -1) {
                entry.categoryName = categoryAndNote.substring(0, bracketIdx).trim();
                String note = categoryAndNote.substring(bracketIdx + 1).trim();
                if (note.endsWith(")")) {
                    note = note.substring(0, note.length() - 1);
                }
                entry.note = note;
            } else {
                entry.categoryName = categoryAndNote.trim();
                entry.note = "";
            }
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSameEntry(GeminiParsedEntry a, GeminiParsedEntry b) {
        if (a == null || b == null)
            return false;
        boolean amountEq = (a.amount != null && b.amount != null && a.amount.compareTo(b.amount) == 0);
        boolean noteEq = (a.note != null ? a.note : "").equals(b.note != null ? b.note : "");
        boolean catEq = (a.categoryName != null ? a.categoryName : "").equals(b.categoryName != null ? b.categoryName : "");
        return amountEq && noteEq && catEq;
    }

    private boolean isRuleBasedConfirmation(String normalized) {
        if (normalized == null || normalized.isBlank())
            return false;
        // Don't confirm if there are amounts (likely a new transaction)
        if (normalized.matches(".*\\d+.*"))
            return false;

        String s = normalized.trim().toLowerCase();
        return s.equals("ok") || s.equals("luu") || s.equals("dung")
                || s.equals("dong y") || s.equals("yes") || s.equals("save")
                || s.equals("confirm") || s.equals("chinh xac") || s.equals("duyet")
                || s.equals("xac nhan") || s.equals("co") || s.equals("vang")
                || s.equals("u tru") || s.equals("chuan") || s.equals("uy") || s.equals("chot");
    }

    // ── Inner helper class ──
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

        static AccountResolution error(String msg) {
            return new AccountResolution(null, false, msg);
        }
    }
}
