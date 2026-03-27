package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.GeminiClientWrapper;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.SpendingAnalyticsService;
import com.example.finance_backend.service.ai.SpendingAnalyticsService.*;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler xử lý các yêu cầu truy vấn thông tin tài chính.
 * Hỗ trợ tính tổng, xem xu hướng, tỷ lệ chi tiêu, tóm tắt tháng, 
 * phân tích sức khỏe tài chính và đưa ra gợi ý thông minh.
 */
@Component
public class QueryTransactionHandler extends BaseIntentHandler {

    private final SpendingAnalyticsService analyticsService;
    private final EntityExtractor entityExtractor;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    public QueryTransactionHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            SpendingAnalyticsService analyticsService,
            EntityExtractor entityExtractor) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.analyticsService = analyticsService;
        this.entityExtractor = entityExtractor;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.QUERY_TRANSACTION, Intent.BUDGET_QUERY, Intent.MONTHLY_SUMMARY);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String language = request.getLanguage();
        Long userId = request.getUserId();
        
        // Handle specific route intents if they come directly
        if (Intent.MONTHLY_SUMMARY.equals(intentResult.getIntent())) {
             return handleMonthlySummary(userId, language);
        }
        if (Intent.BUDGET_QUERY.equals(intentResult.getIntent())) {
             return handleBudgetStatus(request, gemini != null ? gemini.getQuery() : null);
        }

        LocalDate today = LocalDate.now();
        LocalDate start, end;
        String typeFilter, metric;
        String categoryNameFilter = null;
        Integer limit = null;

        if (gemini != null && gemini.query != null) {
            start = dateParser.parseDate(gemini.query.endDate, today.withDayOfMonth(1));
            end = dateParser.parseDate(gemini.query.endDate, today);
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
            LocalDate tmp = start; start = end; end = tmp;
        }
        if (!List.of("EXPENSE", "INCOME", "TRANSFER", "ALL").contains(typeFilter)) typeFilter = "EXPENSE";

        // Dispatch based on metric
        switch (metric) {
            case "TOP_CATEGORY": return buildTopCategoryReply(start, end, typeFilter, language);
            case "LIST": return buildListReply(start, end, typeFilter, limit, categoryNameFilter, gemini, language);
            case "AVERAGE": return buildAverageReply(start, end, typeFilter, language);
            case "TREND": return buildTrendReply(start, end, typeFilter, language);
            case "PERCENTAGE": return buildPercentageReply(start, end, typeFilter, language);
            case "MONTHLY_SUMMARY": return handleMonthlySummary(userId, language);
            case "FINANCIAL_HEALTH": return buildFinancialHealthReply(userId, language);
            case "WEEKLY_PATTERN": return buildWeeklyPatternReply(userId, start, end, language);
            case "SMART_SUGGESTION": return buildSmartSuggestionReply(userId, language);
            case "TOTAL":
            default: return buildTotalReply(start, end, typeFilter, language);
        }
    }

    private AiAssistantResponse buildTotalReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        BigDecimal total = analyticsService.getTotalSpending(start, end, typeFilter);
        String label;
        if ("ALL".equals(typeFilter)) label = responseGenerator.t(language, "Tổng giao dịch", "Total transactions");
        else if ("INCOME".equals(typeFilter)) label = responseGenerator.t(language, "Tổng thu", "Total income");
        else label = responseGenerator.t(language, "Tổng chi", "Total expense");
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.totalReply(start, end, total, label, language)).build();
    }

    private AiAssistantResponse buildTopCategoryReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        List<CategoryTotal> top = analyticsService.getTopCategories(start, end, typeFilter, 1);
        if (top.isEmpty()) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.noDataForTopCategory(language)).build();
        CategoryTotal first = top.get(0);
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.topCategoryReply(start, end, first.getCategoryName(), first.getTotal(), language)).build();
    }

    private AiAssistantResponse buildAverageReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        DailyAverageResult avg = analyticsService.getDailyAverage(start, end, typeFilter);
        String verb = "INCOME".equals(typeFilter) ? responseGenerator.t(language, "thu", "income") : responseGenerator.t(language, "chi", "spending");
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.averageReply(start, end, avg.getAverage(), avg.getDays(), verb, language)).build();
    }

    private AiAssistantResponse buildTrendReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        TrendResult trend = analyticsService.getSpendingTrend(start, end, typeFilter);
        String label = "ALL".equals(typeFilter) ? responseGenerator.t(language, "giao dịch", "transactions") : ("INCOME".equals(typeFilter) ? responseGenerator.t(language, "thu", "income") : responseGenerator.t(language, "chi", "expense"));
        String trendText;
        switch (trend.getTrend()) {
            case "UP": trendText = responseGenerator.t(language, "tăng " + trend.getPercentChange() + "%", "up " + trend.getPercentChange() + "%"); break;
            case "DOWN": trendText = responseGenerator.t(language, "giảm " + trend.getPercentChange().abs() + "%", "down " + trend.getPercentChange().abs() + "%"); break;
            case "NEW": trendText = responseGenerator.t(language, "mới phát sinh", "new activity"); break;
            default: trendText = responseGenerator.t(language, "không đổi", "no change"); break;
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.trendReply(trend.getPreviousStart(), trend.getPreviousEnd(), label, trendText, trend.getCurrentTotal(), trend.getPreviousTotal(), language)).build();
    }

    private AiAssistantResponse buildPercentageReply(LocalDate start, LocalDate end, String typeFilter, String language) {
        PercentageBreakdownResult result = analyticsService.getSpendingByCategory(start, end, typeFilter);
        if (result.getTotal().compareTo(BigDecimal.ZERO) <= 0) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.noDataForPercentage(language)).build();
        List<Map.Entry<String, BigDecimal>> breakdown = result.getBreakdowns().stream().map(cp -> Map.entry(cp.getCategoryName(), cp.getAmount())).collect(Collectors.toList());
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.percentageReply(start, end, breakdown, result.getTotal(), language)).build();
    }

    private AiAssistantResponse buildListReply(LocalDate start, LocalDate end, String typeFilter, Integer limit, String categoryNameFilter, GeminiParseResult gemini, String language) {
        int maxResults = (limit == null || limit <= 0) ? 10 : Math.min(limit, 20);
        List<FinancialEntry> entries = analyticsService.getRecentTransactions(start, end, typeFilter, maxResults);
        if (categoryNameFilter != null && !categoryNameFilter.isBlank()) {
            String normCat = entityExtractor.normalizeCategoryName(categoryNameFilter);
            Map<String, Long> nameToId = getNameToIdMap();
            Long catId = resolveCategoryId(nameToId, normCat, null);
            if (catId != null) entries = entries.stream().filter(e -> Objects.equals(e.getCategoryId(), catId)).collect(Collectors.toList());
        }
        
        // Amount filtering
        if (gemini != null && gemini.getQuery() != null) {
            BigDecimal minVal = gemini.getQuery().getMinAmount();
            BigDecimal maxVal = gemini.getQuery().getMaxAmount();
            if (minVal != null) entries = entries.stream().filter(e -> e.getAmount().compareTo(minVal) >= 0).collect(Collectors.toList());
            if (maxVal != null) entries = entries.stream().filter(e -> e.getAmount().compareTo(maxVal) <= 0).collect(Collectors.toList());
        }

        if (entries.isEmpty()) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.noTransactions(language)).build();
        Map<Long, String> idToName = categoryService.getIdToNameMap();
        StringBuilder sb = new StringBuilder();
        sb.append(responseGenerator.isEnglish(language) ? String.format("Transactions from %s to %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)) : String.format("Danh sách giao dịch từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));
        for (var e : entries) {
            String catName = idToName.getOrDefault(e.getCategoryId(), "Khác");
            String note = e.getNote() != null ? e.getNote() : "";
            sb.append(String.format("- %s • %s", responseGenerator.formatVnd(e.getAmount(), language), catName));
            if (!note.isBlank()) sb.append(" (").append(responseGenerator.trimNote(note)).append(")");
            sb.append("\n");
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(sb.toString().trim()).build();
    }

    private AiAssistantResponse handleBudgetStatus(AiAssistantRequest request, GeminiClientWrapper.GeminiParsedQuery q) {
        String language = request.getLanguage();
        if (q == null || q.getCategoryName() == null) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.t(language, "Bạn muốn kiểm tra ngân sách của danh mục nào?", "Which category budget do you want to check?")).build();
        
        String catName = q.getCategoryName();
        var status = analyticsService.getBudgetStatus(request.getUserId(), catName, LocalDate.now());
        if (status == null) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.noBudgetData(language)).build();
        
        String reply = responseGenerator.budgetStatusReply(status, language);
        return AiAssistantResponse.builder().intent("QUERY").reply(reply).build();
    }

    private AiAssistantResponse handleMonthlySummary(Long userId, String language) {
        LocalDate now = LocalDate.now();
        MonthlySummaryResult summary = analyticsService.getMonthlySummary(userId, now.getMonthValue(), now.getYear());
        List<OverspendingAlert> alerts = analyticsService.getOverspendingAlerts(userId, now.withDayOfMonth(1), now);
        String reply = responseGenerator.monthlySummaryReply(summary, now.getMonthValue(), now.getYear(), language);
        if (!alerts.isEmpty()) reply += "\n\n" + responseGenerator.overspendingAlertReply(alerts, language);
        return AiAssistantResponse.builder().intent("QUERY").reply(reply).build();
    }

    private AiAssistantResponse buildFinancialHealthReply(Long userId, String language) {
        LocalDate now = LocalDate.now();
        FinancialHealthResult health = analyticsService.getFinancialHealth(userId, now.getMonthValue(), now.getYear());
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.financialHealthReply(health, language)).build();
    }

    private AiAssistantResponse buildWeeklyPatternReply(Long userId, LocalDate start, LocalDate end, String language) {
        WeeklyPatternResult pattern = analyticsService.getWeeklyPatterns(userId, start, end);
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.weeklyPatternReply(pattern, language)).build();
    }

    private AiAssistantResponse buildSmartSuggestionReply(Long userId, String language) {
        List<SmartSuggestionResult> suggestions = analyticsService.getSmartSuggestions(userId);
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.smartSuggestionReply(suggestions, language)).build();
    }
}
