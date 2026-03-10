package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.BudgetRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import com.example.finance_backend.service.assistant.state.ConversationStateService.PendingPlanAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Component
public class BudgetHandler extends BaseIntentHandler {

    private final BudgetRepository budgetRepository;
    private final ConversationStateService stateService;
    private final EntityExtractor entityExtractor;

    public BudgetHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            BudgetRepository budgetRepository,
            ConversationStateService stateService,
            EntityExtractor entityExtractor) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.budgetRepository = budgetRepository;
        this.stateService = stateService;
        this.entityExtractor = entityExtractor;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.CREATE_BUDGET, Intent.CREATE_INCOME_GOAL);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String conversationId = request.getConversationId(); //nhớ state
        String language = request.getLanguage();
        String normalized = parsed.getNormalizedText();
        Long userId = request.getUserId();

        EntryType type = intentResult.getIntent() == Intent.CREATE_INCOME_GOAL ? EntryType.INCOME : EntryType.EXPENSE;
        String intentStr = type == EntryType.INCOME ? "CREATE_INCOME_GOAL" : "CREATE_BUDGET";

        PendingPlanAction pending = stateService.computePlanningStateIfAbsent(conversationId, k -> new PendingPlanAction());
        pending.intent = intentStr; //Nếu user chưa nói đủ dữ liệu, hệ thống phải nhớ lại.

        // Trích xuất dữ liệu từ Gemini (theo prompt, data budget/goal ở entries[0])
        if (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) {
            var e = gemini.entries.get(0);
            if (e.amount != null) pending.amount = e.amount;
            if (e.categoryName != null) pending.category = e.categoryName;
            if (e.date != null) pending.month = e.date;
        } else {   //gemini không đủ thì fallback sang parse thủ công
            BigDecimal amt = parsed.getFirstAmount();
            if (amt != null) pending.amount = amt;
            String cat = entityExtractor.inferCategory(normalized);
            if (cat != null) pending.category = cat;
        }

        // Slot filling
        if (pending.amount == null) {
            pending.awaitingField = "AMOUNT";
            return AiAssistantResponse.builder().intent(intentStr).reply(responseGenerator.t(language, "Bạn muốn đặt hạn mức bao nhiêu?", "How much is the limit?")).build();
        }
        if (pending.category == null || pending.category.isBlank()) {
            pending.awaitingField = "CATEGORY";
            return AiAssistantResponse.builder().intent(intentStr).reply(responseGenerator.t(language, "Bạn muốn đặt cho danh mục nào?", "Which category is this for?") + getCategoryListResponse(type, language)).build();
        }

        // Thực hiện lưu dtb lưu bằng categoryid không lưu bằng text
        Map<String, Long> nameToId = getNameToIdMap();
        Long categoryId = resolveCategoryId(nameToId, pending.category, null);
        if (categoryId == null) {
            pending.awaitingField = "CATEGORY";
            return AiAssistantResponse.builder().intent(intentStr).reply(responseGenerator.t(language, "Danh mục không hợp lệ. Vui lòng chọn lại:", "Invalid category. Please choose again:") + getCategoryListResponse(type, language)).build();
        }

        LocalDate now = LocalDate.now();
        LocalDate startDate = now.with(TemporalAdjusters.firstDayOfMonth());
        if (pending.month != null) {
            LocalDate d = dateParser.parseDate(pending.month, now);
            if (d != null) startDate = d.with(TemporalAdjusters.firstDayOfMonth());
        }
        LocalDate endDate = startDate.with(TemporalAdjusters.lastDayOfMonth());

        Budget budget = Budget.builder()
                .userId(userId)
                .categoryId(categoryId)
                .amount(pending.amount)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        budgetRepository.save(budget);
        stateService.removePlanningState(conversationId);

        String catName = categoryRepository.findById(categoryId).map(c -> c.getName()).orElse("");
        return AiAssistantResponse.builder()
                .intent(intentStr).refreshRequired(true)
                .reply(responseGenerator.t(language,
                        String.format("Đã thiết lập %s cho %s: %s (%s - %s).", type == EntryType.INCOME ? "mục tiêu thu" : "hạn mức chi", catName, responseGenerator.formatVnd(pending.amount, language), startDate, endDate),
                        String.format("Set %s for %s: %s (%s - %s).", type == EntryType.INCOME ? "income target" : "budget", catName, responseGenerator.formatVnd(pending.amount, language), startDate, endDate)))
                .build();
    }
}
