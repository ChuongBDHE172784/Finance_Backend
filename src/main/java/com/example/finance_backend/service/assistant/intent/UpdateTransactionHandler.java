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
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedEntry;
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

/**
 * Handler xử lý việc cập nhật/sửa đổi các giao dịch đã tồn tại.
 * Hỗ trợ tìm kiếm giao dịch cần sửa dựa trên từ khóa, ngày tháng hoặc số tiền.
 */
@Component
public class UpdateTransactionHandler extends BaseIntentHandler {

    private final FinancialEntryRepository entryRepository;
    private final EntityExtractor entityExtractor;

    public UpdateTransactionHandler(
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
        return List.of(Intent.UPDATE_TRANSACTION);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String originalMessage = parsed.getOriginalText();
        String language = request.getLanguage();

        List<FinancialEntry> targets;
        GeminiParsedTarget target = gemini != null ? gemini.target : null;

        if (target != null) {
            targets = findTargetEntries(target);
        } else {
            targets = findTargetEntriesFromText(parsed);
        }

        if (targets.isEmpty()) {
            return AiAssistantResponse.builder().intent("UPDATE").reply(responseGenerator.updateNotFound(language)).build();
        }
        if (targets.size() > 1) {
            return AiAssistantResponse.builder().intent("UPDATE").reply(responseGenerator.updateMultipleMatches(language)).build();
        }

        FinancialEntry entry = targets.get(0);

        GeminiParsedEntry newData = (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) ? gemini.entries.get(0) : null;
        if (newData == null) {
            newData = salvageUpdateData(originalMessage);
        }

        if (newData == null) {
            return AiAssistantResponse.builder().intent("UPDATE").reply(responseGenerator.updateWhatToChange(language)).build();
        }

        if (newData.amount != null) entry.setAmount(newData.amount);
        if (newData.note != null && !newData.note.isBlank()) entry.setNote(newData.note);
        if (newData.date != null && !newData.date.isBlank()) {
            LocalDate d = dateParser.parseDate(newData.date, null);
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
}
