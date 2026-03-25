package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.RepeatType;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ScheduleService;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import com.example.finance_backend.service.assistant.state.ConversationStateService.PendingScheduleAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class ScheduleHandler extends BaseIntentHandler {

    private final ScheduleService scheduleService;
    private final ConversationStateService stateService;
    private final EntityExtractor entityExtractor;

    public ScheduleHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            ScheduleService scheduleService,
            ConversationStateService stateService,
            EntityExtractor entityExtractor) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.scheduleService = scheduleService;
        this.stateService = stateService;
        this.entityExtractor = entityExtractor;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.CREATE_SCHEDULE, Intent.DISABLE_SCHEDULE, Intent.LIST_UPCOMING_TRANSACTIONS, Intent.EXPLAIN_TRANSACTION_SOURCE);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String language = request.getLanguage();
        String normalized = parsed.getNormalizedText();
        Long userId = request.getUserId();
        Intent intent = intentResult.getIntent();

        if (Intent.LIST_UPCOMING_TRANSACTIONS.equals(intent)) return handleListUpcoming(userId, language);
        if (Intent.EXPLAIN_TRANSACTION_SOURCE.equals(intent)) return handleExplainSource(normalized, language);
        if (Intent.DISABLE_SCHEDULE.equals(intent)) return handleDisableSchedule(normalized, userId, language);

        // CREATE_SCHEDULE flow
        return handleCreateSchedule(request, parsed, gemini);
    }

    private AiAssistantResponse handleCreateSchedule(AiAssistantRequest request, ParsedMessage parsed, GeminiParseResult gemini) {
        String conversationId = request.getConversationId();
        String language = request.getLanguage();
        Long userId = request.getUserId();
        
        PendingScheduleAction pending = stateService.computeSchedulePlanningStateIfAbsent(conversationId, k -> new PendingScheduleAction());

        if (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) {
            var e = gemini.entries.get(0);
            if (e.amount != null) pending.amount = e.amount;
            if (e.categoryName != null) pending.categoryName = e.categoryName;
            if (e.repeatType != null) pending.repeatType = e.repeatType.toUpperCase();
            if (e.note != null) pending.note = e.note;
        } else {
            BigDecimal amt = parsed.getFirstAmount();
            if (amt != null) pending.amount = amt;
            String cat = entityExtractor.inferCategory(parsed.getNormalizedText());
            if (cat != null) pending.categoryName = cat;
            String repeat = entityExtractor.inferRepeatType(parsed.getNormalizedText());
            if (repeat != null) pending.repeatType = repeat;
        }

        // Slot filling
        if (pending.amount == null) {
            pending.awaitingField = "AMOUNT";
            return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Số tiền định kỳ là bao nhiêu?", "How much is the recurring amount?")).build();
        }
        if (pending.categoryName == null) {
            pending.awaitingField = "CATEGORY";
            return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Cho danh mục nào?", "For which category?") + getCategoryListResponse(com.example.finance_backend.entity.EntryType.EXPENSE, language)).build();
        }
        if (pending.repeatType == null || "NONE".equals(pending.repeatType)) {
            pending.awaitingField = "REPEAT";
            return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Lặp lại thế nào? (Hàng ngày, hàng tuần, hàng tháng)", "What is the repetition? (Daily, Weekly, Monthly)")).build();
        }

        // Account resolution
        AccountResolution acctRes = resolveAccount(parsed.getOriginalText(), request.getAccountId(), userId, language);
        if (acctRes.needsSelection) {
            pending.awaitingField = "ACCOUNT";
            return AiAssistantResponse.builder().intent("NEED_ACCOUNT").needsAccountSelection(true).reply(responseGenerator.needAccountSelection(language)).build();
        }
        if (acctRes.errorMessage != null) return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(acctRes.errorMessage).build();
        
        pending.accountId = acctRes.accountId;

        // Perform save
        Map<String, Long> nameToId = getNameToIdMap();
        Long categoryId = resolveCategoryId(nameToId, pending.categoryName, resolveFallbackCategoryId(nameToId));
        
        RepeatType rt;
        try { rt = RepeatType.valueOf(pending.repeatType); } catch (Exception ex) { rt = RepeatType.MONTHLY; }

        ScheduleDTO dto = ScheduleDTO.builder()
                .userId(userId)
                .categoryId(categoryId)
                .accountId(pending.accountId)
                .amount(pending.amount)
                .repeatType(rt)
                .note(pending.note != null ? pending.note : parsed.getOriginalText())
                .isActive(true)
                .startDate(LocalDateTime.now())
                .build();
        
        scheduleService.createSchedule(dto);
        stateService.removeSchedulePlanningState(conversationId);
        
        String catName = categoryRepository.findById(categoryId).map(c -> c.getName()).orElse("Khác");
        return AiAssistantResponse.builder()
                .intent("CREATE_SCHEDULE").refreshRequired(true)
                .reply(responseGenerator.createScheduleSuccess(catName, pending.amount, pending.repeatType, LocalDate.now(), language))
                .build();
    }

    private AiAssistantResponse handleListUpcoming(Long userId, String language) {
        return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.t(language, "Tính năng xem lịch trình sắp tới đang được phát triển.", "Upcoming transactions feature is under development.")).build();
    }

    private AiAssistantResponse handleExplainSource(String message, String language) {
        return AiAssistantResponse.builder().intent("ADVICE").reply(responseGenerator.scheduleExplanationReply("Tiền nhà", "MONTHLY", language)).build();
    }

    private AiAssistantResponse handleDisableSchedule(String message, Long userId, String language) {
        return AiAssistantResponse.builder().intent("UPDATE").refreshRequired(true).reply(responseGenerator.disableScheduleSuccess(language)).build();
    }
}
