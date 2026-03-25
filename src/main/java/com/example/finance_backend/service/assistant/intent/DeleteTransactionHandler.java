package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedTarget;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class DeleteTransactionHandler extends BaseIntentHandler {

    private final FinancialEntryRepository entryRepository;
    private final EntityExtractor entityExtractor;

    public DeleteTransactionHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            FinancialEntryRepository entryRepository,
            EntityExtractor entityExtractor) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.entryRepository = entryRepository;
        this.entityExtractor = entityExtractor;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.DELETE_TRANSACTION);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String language = request.getLanguage();
        List<FinancialEntry> targets;
        GeminiParsedTarget target = gemini != null ? gemini.target : null;
        boolean deleteAll = target != null && Boolean.TRUE.equals(target.deleteAll);

        if (target != null) {
            targets = findTargetEntries(target);
        } else {
            targets = findTargetEntriesFromText(parsed);
        }

        String normalized = parsed.getNormalizedText();
        if (!deleteAll) {
            deleteAll = normalized.contains("tat ca") || normalized.contains("toan bo")
                    || normalized.contains("het") || normalized.contains("xoa het")
                    || normalized.contains("all") || normalized.contains("everything")
                    || normalized.contains("remove all") || normalized.contains("delete all");
        }

        if (targets.isEmpty() && (normalized.contains("huy") || normalized.contains("lam lai") || normalized.contains("cancel") || normalized.contains("discard"))) {
            return AiAssistantResponse.builder()
                    .intent("DELETE")
                    .reply(responseGenerator.t(language, "Đã hủy các giao dịch đang chờ.", "Pending transactions discarded."))
                    .build();
        }

        if (targets.isEmpty()) {
            return AiAssistantResponse.builder().intent("DELETE").reply(responseGenerator.deleteNotFound(language)).build();
        }
        if (!deleteAll && targets.size() > 1) {
            return AiAssistantResponse.builder().intent("DELETE").reply(responseGenerator.deleteMultipleMatches(language)).build();
        }

        int deletedCount = targets.size();
        String detail = deletedCount == 1 ? responseGenerator.formatVnd(targets.get(0).getAmount(), language) + " - " + targets.get(0).getNote() : null;
        entryRepository.deleteAll(targets);

        return AiAssistantResponse.builder()
                .intent("DELETE").refreshRequired(true)
                .reply(responseGenerator.deleteSuccess(deletedCount, detail, language))
                .build();
    }

    private List<FinancialEntry> findTargetEntries(GeminiParsedTarget target) {
        if (target == null) return List.of();
        String normalizedCat = target.categoryName != null ? entityExtractor.normalizeCategoryName(target.categoryName) : null;
        LocalDate searchDate = dateParser.parseDate(target.date, null);
        LocalDate start = searchDate != null ? searchDate : LocalDate.now().minusDays(30);
        LocalDate end = searchDate != null ? searchDate : LocalDate.now();

        List<FinancialEntry> entries = entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream().filter(e -> {
            if (target.amount != null && e.getAmount().compareTo(target.amount) != 0) return false;
            if (normalizedCat != null && !normalizedCat.isBlank()) {
                String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "").toLowerCase(Locale.ROOT);
                String normCatName = textPreprocessor.normalizeVietnamese(catName);
                String normTarget = textPreprocessor.normalizeVietnamese(normalizedCat.toLowerCase(Locale.ROOT));
                if (!normCatName.contains(normTarget) && !normTarget.contains(normCatName)) return false;
            }
            if (target.noteKeywords != null && !target.noteKeywords.isBlank()) {
                String note = e.getNote() != null ? textPreprocessor.normalizeVietnamese(e.getNote().toLowerCase(Locale.ROOT)) : "";
                String keywords = keywordCleaner.cleanKeywords(textPreprocessor.normalizeVietnamese(target.noteKeywords.toLowerCase(Locale.ROOT)));
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

        List<FinancialEntry> entries = entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        return entries.stream().filter(e -> {
            if (amount != null && e.getAmount().compareTo(amount) != 0) return false;
            return true;
        }).collect(Collectors.toList());
    }
}
