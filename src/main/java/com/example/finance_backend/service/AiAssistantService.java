package com.example.finance_backend.service;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.repository.BudgetRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Assistant orchestrator — delegates to pipeline components:
 * TextPreprocessor → IntentDetector → EntityExtractor → ConversationContextManager
 * → SpendingAnalyticsService → ResponseGenerator
 *
 * Also uses GeminiClientWrapper for complex NLU when rules fail.
 */
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // ── Pipeline components ──
    private final TextPreprocessor textPreprocessor;
    private final IntentDetector intentDetector;
    private final EntityExtractor entityExtractor;
    private final ConversationContextManager contextManager;
    private final GeminiClientWrapper geminiClient;
    private final ResponseGenerator responseGenerator;
    private final SpendingAnalyticsService analyticsService;
    private final FinancialScoreEngine financialScoreEngine;

    // ── Data services ──
    private final FinancialEntryService entryService;
    private final FinancialEntryRepository entryRepository;
    private final AccountRepository accountRepository;
    private final AiMessageRepository aiMessageRepository;
    private final CategoryService categoryService;
    private final BudgetRepository budgetRepository;

    // ═════════════════════════════════════════════════════════
    // MAIN PIPELINE ENTRY POINT
    // ═════════════════════════════════════════════════════════

    public AiAssistantResponse handle(AiAssistantRequest request) {
        final String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        // Step 1: Pre-process text
        ParsedMessage parsed = textPreprocessor.preprocess(message, request.getLanguage());
        String language = parsed.getLanguage();

        // Empty input check
        if (message.isEmpty() && (request.getBase64Image() == null || request.getBase64Image().isBlank())) {
            AiAssistantResponse resp = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(responseGenerator.emptyInputMessage(language))
                    .build();
            resp.setConversationId(conversationId);
            return resp;
        }

        // Step 2: Load conversation history
        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        // Step 3: Try Gemini for complex understanding
        GeminiParseResult geminiResult = null;
        try {
            geminiResult = geminiClient.parse(message, request.getBase64Image(), history, language);
        } catch (Exception e) {
            log.warn("Gemini parse failed, falling back to rules: {}", e.getMessage());
        }

        // Step 4: Determine intent (hybrid: Gemini + rule-based)
        IntentResult intentResult;
        if (geminiResult != null && geminiResult.intent != null
                && !geminiResult.intent.isBlank()
                && !"UNKNOWN".equalsIgnoreCase(geminiResult.intent)) {
            // Gemini succeeded — use its intent
            intentResult = mapGeminiIntent(geminiResult.intent);
        } else {
            // Rule-based fallback
            intentResult = intentDetector.detect(parsed);
        }

        // Step 5: Dispatch by intent
        AiAssistantResponse response;
        switch (intentResult.getIntent()) {
            case INSERT_TRANSACTION:
                response = handleInsert(message, parsed, geminiResult, request.getAccountId(),
                        request.getUserId(), language);
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
            case BUDGET_QUERY:
                response = handleBudgetQuery(request.getUserId(), language);
                break;
            case MONTHLY_SUMMARY:
                response = handleMonthlySummary(request.getUserId(), language);
                break;
            case FINANCIAL_SCORE:
                response = handleFinancialScore(request.getUserId(), language);
                break;
            case SET_BUDGET:
                response = handleSetBudget(parsed, geminiResult, request.getUserId(), language);
                break;
            case FINANCIAL_ADVICE:
            case GENERAL_CHAT:
                response = handleAdvice(geminiResult, request.getUserId(), language);
                break;
            default:
                // Context-aware: check if this is a follow-up to a clarification
                response = handleContextualFallback(parsed, history, request, language);
                break;
        }

        return finalizeResponse(response, conversationId, message, request.getBase64Image(), request.getUserId());
    }

    // ═════════════════════════════════════════════════════════
    // INSERT HANDLER
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleInsert(String originalMessage, ParsedMessage parsed,
                                              GeminiParseResult gemini,
                                              Long forcedAccountId, Long userId, String language) {
        // Extract transaction slots from Gemini or rules
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

        if (slots.isEmpty()) {
            return AiAssistantResponse.builder()
                    .intent("INSERT")
                    .refreshRequired(false)
                    .reply(responseGenerator.insertEmpty(language))
                    .build();
        }

        // Check for missing slots — ask clarification
        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(
                parsed.getOriginalText()); // Will use conversationId properly in finalize
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

        // Resolve account
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

        for (TransactionSlot slot : slots) {
            if (slot == null || slot.getAmount() == null || slot.getAmount().compareTo(BigDecimal.ZERO) <= 0)
                continue;

            String type = slot.getType() == null || slot.getType().isBlank()
                    ? "EXPENSE" : slot.getType().toUpperCase(Locale.ROOT);
            if (!"EXPENSE".equals(type) && !"INCOME".equals(type)) type = "EXPENSE";

            String resolvedCategory = entityExtractor.normalizeCategoryName(slot.getCategoryName());
            if ("INCOME".equals(type)) resolvedCategory = "Nạp tiền";
            else if (resolvedCategory == null || resolvedCategory.isBlank()) resolvedCategory = null;

            Long preferredFallback = "INCOME".equals(type) ? incomeCategoryId : fallbackCategoryId;
            Long categoryId = resolveCategoryId(nameToId, resolvedCategory, preferredFallback);
            LocalDate date = parseDate(slot.getDate(), LocalDate.now());
            String note = slot.getNote() != null && !slot.getNote().isBlank()
                    ? slot.getNote().trim() : originalMessage;
            if (note.length() > 2000) note = note.substring(0, 1997) + "...";

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
    // QUERY HANDLER (delegates to SpendingAnalyticsService)
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleQuery(ParsedMessage parsed, GeminiParseResult gemini, Long userId, String language) {
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

        if (end.isBefore(start)) { LocalDate tmp = start; start = end; end = tmp; }
        if (!List.of("EXPENSE", "INCOME", "TRANSFER", "ALL").contains(typeFilter)) typeFilter = "EXPENSE";

        // Use SpendingAnalyticsService for analytics
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
        if ("ALL".equals(typeFilter)) label = responseGenerator.t(language, "Tổng giao dịch", "Total transactions");
        else if ("INCOME".equals(typeFilter)) label = responseGenerator.t(language, "Tổng thu", "Total income");
        else label = responseGenerator.t(language, "Tổng chi", "Total expense");
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.totalReply(start, end, total, label, language))
                .build();
    }

    private AiAssistantResponse buildTopCategoryReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        List<CategoryTotal> top = analyticsService.getTopCategories(start, end, typeFilter, 1);
        if (top.isEmpty()) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.noDataForTopCategory(language)).build();
        }
        CategoryTotal first = top.get(0);
        return AiAssistantResponse.builder()
                .intent("QUERY")
                .reply(responseGenerator.topCategoryReply(start, end, first.getCategoryName(), first.getTotal(), language))
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

    private AiAssistantResponse buildPercentageReply(LocalDate start, LocalDate end, String typeFilter, String language) {
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
            if (!note.isBlank()) sb.append(" (").append(responseGenerator.trimNote(note)).append(")");
            sb.append("\n");
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(sb.toString().trim()).build();
    }

    // ═════════════════════════════════════════════════════════
    // UPDATE HANDLER
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleUpdate(String originalMessage, ParsedMessage parsed,
                                              GeminiParseResult gemini, Long forcedAccountId, String language) {
        List<FinancialEntry> targets;
        GeminiParsedTarget target = gemini != null ? gemini.target : null;

        if (target != null) {
            targets = findTargetEntries(target);
        } else {
            // Rule-based target detection
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

        // Get new data
        GeminiParsedEntry newData = (gemini != null && gemini.entries != null && !gemini.entries.isEmpty())
                ? gemini.entries.get(0) : null;

        if (newData == null) {
            // Try salvage from message pattern "thành/to"
            newData = salvageUpdateData(originalMessage);
        }

        if (newData == null) {
            return AiAssistantResponse.builder().intent("UPDATE")
                    .reply(responseGenerator.updateWhatToChange(language)).build();
        }

        if (newData.amount != null) entry.setAmount(newData.amount);
        if (newData.note != null && !newData.note.isBlank()) entry.setNote(newData.note);
        if (newData.date != null && !newData.date.isBlank()) {
            LocalDate d = parseDate(newData.date, null);
            if (d != null) entry.setTransactionDate(d);
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
    // DELETE HANDLER
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
            String normalized = parsed.getNormalizedText();
            deleteAll = normalized.contains("tat ca") || normalized.contains("het")
                    || normalized.contains("all") || normalized.contains("everything");
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
    // ADVICE HANDLER
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleAdvice(GeminiParseResult gemini, Long userId, String language) {
        String reply = (gemini != null && gemini.adviceReply != null && !gemini.adviceReply.isBlank())
                ? gemini.adviceReply
                : responseGenerator.t(language,
                "Mình có thể giúp bạn quản lý chi tiêu, ghi nhận thu chi, và phân tích tài chính. Hãy thử hỏi nhé!",
                "I can help you manage expenses, record transactions, and analyze finances. Just ask!");

        // Enrich with financial context if userId is available
        if (userId != null) {
            try {
                LocalDate now = LocalDate.now();
                FinancialScoreResult score = financialScoreEngine.computeScore(userId, now.getMonthValue(), now.getYear());
                String scoreContext = responseGenerator.t(language,
                        "\n\n📊 Điểm tài chính hiện tại: " + score.getTotalScore() + "/100 (Hạng " + score.getGrade() + ")",
                        "\n\n📊 Current Financial Score: " + score.getTotalScore() + "/100 (Grade " + score.getGrade() + ")");
                reply += scoreContext;
            } catch (Exception e) {
                log.debug("Could not compute financial score for advice context: {}", e.getMessage());
            }
        }

        return AiAssistantResponse.builder().intent("ADVICE").reply(reply).build();
    }

    // ═════════════════════════════════════════════════════════
    // BUDGET QUERY HANDLER
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
    // MONTHLY SUMMARY HANDLER
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleMonthlySummary(Long userId, String language) {
        LocalDate now = LocalDate.now();
        MonthlySummaryResult summary = analyticsService.getMonthlySummary(userId, now.getMonthValue(), now.getYear());
        // Append overspending alerts
        List<OverspendingAlert> alerts = analyticsService.getOverspendingAlerts(
                userId, now.withDayOfMonth(1), now);
        String reply = responseGenerator.monthlySummaryReply(summary, now.getMonthValue(), now.getYear(), language);
        if (!alerts.isEmpty()) {
            reply += "\n\n" + responseGenerator.overspendingAlertReply(alerts, language);
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(reply).build();
    }

    // ═════════════════════════════════════════════════════════
    // FINANCIAL SCORE HANDLER
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleFinancialScore(Long userId, String language) {
        if (userId == null) {
            return AiAssistantResponse.builder().intent("QUERY")
                    .reply(responseGenerator.t(language,
                            "Cần đăng nhập để xem điểm tài chính.",
                            "Please log in to see your financial score.")).build();
        }
        LocalDate now = LocalDate.now();
        FinancialScoreResult score = financialScoreEngine.computeScore(userId, now.getMonthValue(), now.getYear());
        return AiAssistantResponse.builder().intent("QUERY")
                .reply(responseGenerator.financialScoreReply(score, language)).build();
    }

    // ═════════════════════════════════════════════════════════
    // ADVANCED QUERY BUILDERS
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
    // CONTEXTUAL FALLBACK (handles follow-up messages)
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleContextualFallback(ParsedMessage parsed, List<AiMessage> history,
                                                          AiAssistantRequest request, String language) {
        // Try to fill slots from conversation context
        List<TransactionSlot> slots = entityExtractor.extractTransactionSlots(parsed);
        slots = contextManager.resolveWithContext(slots,
                IntentResult.builder().intent(Intent.INSERT_TRANSACTION).build(), parsed, history);

        // If context resolved to a complete transaction, insert it
        boolean hasComplete = slots.stream().anyMatch(TransactionSlot::isComplete);
        if (hasComplete && parsed.hasAmounts()) {
            return handleInsert(parsed.getOriginalText(), parsed, null,
                    request.getAccountId(), request.getUserId(), language);
        }

        // Ask clarification
        String clarification = contextManager.detectClarificationNeeded(slots,
                IntentResult.builder().intent(Intent.INSERT_TRANSACTION).build(), language);
        if (clarification != null && slots.stream().anyMatch(s -> s.getNote() != null && !s.getNote().isBlank())) {
            return AiAssistantResponse.builder()
                    .intent("INSERT").refreshRequired(false).reply(clarification).build();
        }

        return AiAssistantResponse.builder()
                .intent("UNKNOWN")
                .reply(responseGenerator.unknownMessage(language))
                .build();
    }

    // ═════════════════════════════════════════════════════════
    // TARGET ENTRY SEARCH
    // ═════════════════════════════════════════════════════════

    private List<FinancialEntry> findTargetEntries(GeminiParsedTarget target) {
        if (target == null) return List.of();
        String normalizedCat = target.categoryName != null
                ? entityExtractor.normalizeCategoryName(target.categoryName) : null;
        LocalDate searchDate = parseDate(target.date, null);
        LocalDate start = searchDate != null ? searchDate : LocalDate.now().minusDays(30);
        LocalDate end = searchDate != null ? searchDate : LocalDate.now();

        List<FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream().filter(e -> {
            if (target.amount != null && e.getAmount().compareTo(target.amount) != 0) return false;
            if (normalizedCat != null && !normalizedCat.isBlank()) {
                String catName = categoryService.getIdToNameMap()
                        .getOrDefault(e.getCategoryId(), "").toLowerCase(Locale.ROOT);
                String normCatName = textPreprocessor.normalizeVietnamese(catName);
                String normTarget = textPreprocessor.normalizeVietnamese(normalizedCat.toLowerCase(Locale.ROOT));
                if (!normCatName.contains(normTarget) && !normTarget.contains(normCatName)) return false;
            }
            if (target.noteKeywords != null && !target.noteKeywords.isBlank()) {
                String note = e.getNote() != null ? textPreprocessor.normalizeVietnamese(e.getNote().toLowerCase(Locale.ROOT)) : "";
                String keywords = cleanKeywords(textPreprocessor.normalizeVietnamese(target.noteKeywords.toLowerCase(Locale.ROOT)));
                if (!keywords.isBlank() && !note.contains(keywords)) return false;
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
            if (amount != null && e.getAmount().compareTo(amount) != 0) return false;
            return true;
        }).collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═════════════════════════════════════════════════════════

    private IntentResult mapGeminiIntent(String geminiIntent) {
        String upper = geminiIntent.trim().toUpperCase(Locale.ROOT);
        Intent intent = switch (upper) {
            case "INSERT" -> Intent.INSERT_TRANSACTION;
            case "QUERY" -> Intent.QUERY_TRANSACTION;
            case "UPDATE" -> Intent.UPDATE_TRANSACTION;
            case "DELETE" -> Intent.DELETE_TRANSACTION;
            case "ADVICE" -> Intent.FINANCIAL_ADVICE;
            case "SET_BUDGET" -> Intent.SET_BUDGET;
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
            if (notePart.length() > 2) entry.note = parts[1].trim();
            String catMatch = entityExtractor.inferCategory(textPreprocessor.normalizeVietnamese(parts[1]));
            if (catMatch != null) entry.categoryName = catMatch;
            return entry;
        }
        return null;
    }

    private String cleanKeywords(String keywords) {
        return keywords
                .replaceAll("\\b(sua|doi|cap nhat|thanh|sang|thay|den|xoa|huy|bo|giup|giao dich|khoan|cai|nay|tat ca|het|hom nay|hom qua|ngay|thang|nam|vi|momo|tien|chi|tieu|vua|nay|chieu|sang|toi|update|change|edit|set|delete|remove|transaction|entry|this|that|all|everything|today|yesterday|day|month|year|wallet|money|expense|income|spend|spent|buy|payment|pay|recent|nap|nap tien|gui|rut|banking|transfer)\\b", "")
                .replaceAll("[0-9.,dđkK]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private AccountResolution resolveAccount(String message, Long forcedAccountId, Long userId, String language) {
        List<Account> accounts = (userId != null)
                ? accountRepository.findByUserIdOrderByNameAsc(userId)
                : accountRepository.findAll();
        if (accounts.isEmpty()) return AccountResolution.none();
        if (forcedAccountId != null) {
            boolean exists = accounts.stream().anyMatch(a -> Objects.equals(a.getId(), forcedAccountId));
            if (exists) return AccountResolution.selected(forcedAccountId);
            return AccountResolution.error(responseGenerator.t(language,
                    "Ví/tài khoản không tồn tại.", "Wallet/account does not exist."));
        }
        if (accounts.size() == 1) return AccountResolution.selected(accounts.get(0).getId());
        // Try to match account name in message
        if (message != null && !message.isBlank()) {
            String normMsg = textPreprocessor.normalizeVietnamese(message);
            Long bestId = null; int bestLen = 0;
            for (Account a : accounts) {
                String name = a.getName() == null ? "" : a.getName();
                String normName = textPreprocessor.normalizeVietnamese(name);
                if (!normName.isBlank() && normMsg.contains(normName) && normName.length() > bestLen) {
                    bestLen = normName.length();
                    bestId = a.getId();
                }
            }
            if (bestId != null) return AccountResolution.selected(bestId);
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
        if (name == null || name.isBlank()) return fallback;
        Long id = nameToId.get(name.trim().toLowerCase(Locale.ROOT));
        if (id != null) return id;
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
        if (date == null || date.isBlank()) return fallback;
        try { return LocalDate.parse(date.trim(), DATE_FMT); }
        catch (Exception e) { return fallback; }
    }

    private AiAssistantResponse finalizeResponse(AiAssistantResponse response, String conversationId,
                                                  String message, String base64Image, Long userId) {
        response.setConversationId(conversationId);
        boolean hasImage = base64Image != null && !base64Image.isBlank();
        if ((message != null && !message.isBlank()) || hasImage) {
            String safeContent = (message == null || message.isBlank()) ? "[Gửi ảnh hóa đơn]" : message.trim();
            saveMessage(conversationId, "USER", safeContent, userId);
            if (response.getReply() != null) {
                saveMessage(conversationId, "ASSISTANT", response.getReply(), userId);
            }
        }
        return response;
    }

    public List<AiMessage> getHistory(Long userId) {
        if (userId == null) return List.of();
        // Load only the latest 50 messages to avoid large payloads
        List<AiMessage> all = aiMessageRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (all.size() > 50) return all.subList(all.size() - 50, all.size());
        return all;
    }

    private void saveMessage(String conversationId, String role, String content, Long userId) {
        String safe = content == null ? "" : content.trim();
        if (safe.length() > 4000) safe = safe.substring(0, 3997) + "...";
        aiMessageRepository.save(AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(role)
                .content(safe)
                .build());
    }

    // ═════════════════════════════════════════════════════════
    // BUDGET SETTER
    // ═════════════════════════════════════════════════════════

    private AiAssistantResponse handleSetBudget(ParsedMessage parsed, GeminiParseResult gemini, Long userId, String language) {
        if (userId == null) {
            return AiAssistantResponse.builder().intent("ADVICE")
                    .reply(responseGenerator.t(language, "Bạn cần đăng nhập để đặt ngân sách.", "Please log in to set a budget.")).build();
        }

        GeminiParsedEntry data = (gemini != null && gemini.entries != null && !gemini.entries.isEmpty())
                ? gemini.entries.get(0) : null;
        
        if (data == null || data.amount == null || data.amount.compareTo(BigDecimal.ZERO) <= 0) {
            return AiAssistantResponse.builder().intent("ADVICE")
                    .reply(responseGenerator.t(language,
                            "Bạn muốn đặt ngân sách bao nhiêu? (Ví dụ: Ngân sách ăn uống 5 triệu)",
                            "How much budget do you want to set? (e.g., Set food budget to 5M)")).build();
        }

        String normCat = entityExtractor.normalizeCategoryName(data.categoryName != null ? data.categoryName : parsed.getNormalizedText());
        Map<String, Long> nameToId = getNameToIdMap();
        Long catId = resolveCategoryId(nameToId, normCat, null);

        if (catId == null) {
            return AiAssistantResponse.builder().intent("ADVICE")
                    .reply(responseGenerator.t(language,
                            "Mình không tìm thấy danh mục này. Bạn hãy chọn danh mục cụ thể nhé.",
                            "I couldn't find this category. Please specify a valid category.")).build();
        }

        // Determine dates (default to current month, unless Gemini extracted a date)
        LocalDate today = LocalDate.now();
        LocalDate start = today.withDayOfMonth(1);
        if (data.date != null) {
             LocalDate parsedDate = parseDate(data.date, today);
             if (parsedDate.isAfter(today)) {
                 start = parsedDate.withDayOfMonth(1);
             }
        }
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        // Upsert budget
        Optional<Budget> existing = budgetRepository.findFirstByUserIdAndCategoryIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                userId, catId, start, end);
        
        Budget budget = existing.orElse(new Budget());
        budget.setUserId(userId);
        budget.setCategoryId(catId);
        budget.setAmount(data.amount);
        budget.setStartDate(start);
        budget.setEndDate(end);
        budgetRepository.save(budget);

        String catName = categoryService.getIdToNameMap().get(catId);
        String reply = responseGenerator.t(language,
                String.format("Vâng, mình đã ghi nhận hạn mức ngân sách cho danh mục **%s** là **%s** cho tháng %d/%d.",
                        catName, responseGenerator.formatVnd(data.amount, language), start.getMonthValue(), start.getYear()),
                String.format("Okay, I've set a **%s** budget for **%s** for month %d/%d.",
                        responseGenerator.formatVnd(data.amount, language), catName, start.getMonthValue(), start.getYear()));

        return AiAssistantResponse.builder().intent("QUERY").refreshRequired(true).reply(reply).build();
    }

    // ── Inner helper class ──
    private static class AccountResolution {
        final Long accountId;
        final boolean needsSelection;
        final String errorMessage;
        private AccountResolution(Long accountId, boolean needsSelection, String errorMessage) {
            this.accountId = accountId; this.needsSelection = needsSelection; this.errorMessage = errorMessage;
        }
        static AccountResolution selected(Long accountId) { return new AccountResolution(accountId, false, null); }
        static AccountResolution needsSelection() { return new AccountResolution(null, true, null); }
        static AccountResolution none() { return new AccountResolution(null, false, null); }
        static AccountResolution error(String msg) { return new AccountResolution(null, false, msg); }
    }
}
