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
    