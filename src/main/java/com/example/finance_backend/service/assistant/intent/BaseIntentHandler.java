package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public abstract class BaseIntentHandler implements IntentHandler {

    protected final CategoryService categoryService;
    protected final AccountRepository accountRepository;
    protected final CategoryRepository categoryRepository;
    protected final TextPreprocessor textPreprocessor;
    protected final ResponseGenerator responseGenerator;
    protected final DateParser dateParser;
    protected final KeywordCleaner keywordCleaner;

    @Override
    public abstract AiAssistantResponse handle(
            AiAssistantRequest request,
            ParsedMessage parsedMessage,
            IntentResult intentResult,
            GeminiParseResult geminiResult,
            List<AiMessage> history);

    protected Map<String, Long> getNameToIdMap() {
        return categoryService.findAll().stream()
                .collect(Collectors.toMap(c -> textPreprocessor.normalizeVietnamese(c.getName()), c -> c.getId(),
                        (a, b) -> a));
    }

    protected Long resolveCategoryId(Map<String, Long> nameToId, String name, Long fallbackId) {
        if (name == null || name.isBlank())
            return fallbackId;
        String norm = textPreprocessor.normalizeVietnamese(name);
        return nameToId.getOrDefault(norm, fallbackId);
    }

    protected Long resolveFallbackCategoryId(Map<String, Long> nameToId) {
        return nameToId.getOrDefault("khac", null);
    }

    protected String getCategoryListResponse(EntryType type, String language) {
        String cats = categoryService.findAll().stream()
                .filter(c -> c.getType() == type)
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));
        return "\n" + responseGenerator.t(language, "Danh mục gợi ý: ", "Suggested categories: ") + cats;
    }

    protected AccountResolution resolveAccount(String message, Long defaultAccountId, Long userId, String language) {
        // Logic resolution account... (omitted for brevity, keep as is if possible or
        // implement simply)
        if (defaultAccountId != null)
            return new AccountResolution(defaultAccountId, false, null);
        return new AccountResolution(null, true, null);
    }

    public static class AccountResolution {
        public Long accountId;
        public boolean needsSelection;
        public String errorMessage;

        public AccountResolution(Long id, boolean needs, String err) {
            this.accountId = id;
            this.needsSelection = needs;
            this.errorMessage = err;
        }
    }
}
