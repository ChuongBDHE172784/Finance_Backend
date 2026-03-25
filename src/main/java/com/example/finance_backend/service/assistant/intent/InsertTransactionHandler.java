package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.FinancialEntryService;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedEntry;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class InsertTransactionHandler extends BaseIntentHandler {

    private final FinancialEntryService entryService;
    private final ConversationStateService stateService;
    private final EntityExtractor entityExtractor;

    public InsertTransactionHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            FinancialEntryService entryService,
            ConversationStateService stateService,
            EntityExtractor entityExtractor) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.entryService = entryService;
        this.stateService = stateService;
        this.entityExtractor = entityExtractor;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.INSERT_TRANSACTION);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String originalMessage = parsed.getOriginalText();
        String language = request.getLanguage();
        Long userId = request.getUserId();

        // ── TRÍCH XUẤT CÁC Ô (SLOTS) ──
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

        // Khôi phục các bản nháp trước đó từ lịch sử và hợp nhất thông minh
        List<GeminiParsedEntry> previousDrafts = stateService.extractDraftEntriesFromHistory(history);
        if (!previousDrafts.isEmpty()) {
            for (GeminiParsedEntry prev : previousDrafts) {
                // Nếu bản nháp mới đã có một mục cho cùng sản phẩm/dịch vụ này (cùng danh mục và nội dung),
                // chúng ta coi bản mới là nguồn sự thật (có thể là một bản cập nhật).
                // Chỉ thêm từ lịch sử nếu không có sự trùng lặp về nhận diện.
                boolean exists = draftEntries.stream().anyMatch(curr -> isSameIdentity(curr, prev));
                if (!exists) {
                    draftEntries.add(0, prev);
                }
            }
        }

        // ── QUYẾT ĐỊNH Ý ĐỊNH ──
        boolean isConfirmation = (gemini != null && Boolean.TRUE.equals(gemini.isConfirmation))
                || keywordCleaner.isRuleBasedConfirmation(parsed.getNormalizedText());

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

        // ── LUỒNG CÔNG VIỆC LƯU ──
        AccountResolution acctRes = resolveAccount(originalMessage, request.getAccountId(), userId, language);
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
        
        Long accountId = acctRes.accountId;
        // ... (phần còn lại của logic lưu từ AiAssistantService)
        return processSaving(draftEntries, accountId, userId, language, originalMessage);
    }

    private AiAssistantResponse processSaving(List<GeminiParsedEntry> draftEntries, Long accountId, Long userId, String language, String originalMessage) {
        Map<String, Long> nameToId = getNameToIdMap();
        Long fallbackCategoryId = resolveFallbackCategoryId(nameToId);
        if (fallbackCategoryId == null) {
            return AiAssistantResponse.builder().intent("INSERT").reply(responseGenerator.noCategories(language)).build();
        }
        Long incomeCategoryId = resolveCategoryId(nameToId, "Nạp tiền", fallbackCategoryId);

        List<String> savedLines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int createdCount = 0;

        for (GeminiParsedEntry slot : draftEntries) {
            if (slot == null || slot.getAmount() == null || slot.getAmount().compareTo(BigDecimal.ZERO) <= 0)
                continue;

            String type = slot.getType() == null || slot.getType().isBlank() ? "EXPENSE" : slot.getType().toUpperCase(Locale.ROOT);
            if (!"EXPENSE".equals(type) && !"INCOME".equals(type)) type = "EXPENSE";

            String resolvedCategory = entityExtractor.normalizeCategoryName(slot.getCategoryName());
            if ("INCOME".equals(type)) resolvedCategory = "Nạp tiền";
            
            Long preferredFallback = "INCOME".equals(type) ? incomeCategoryId : fallbackCategoryId;
            Long categoryId = resolveCategoryId(nameToId, resolvedCategory, preferredFallback);
            LocalDate date = dateParser.parseDate(slot.getDate(), LocalDate.now());
            String note = slot.getNote() != null && !slot.getNote().isBlank() ? slot.getNote().trim() : originalMessage;
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
                FinancialEntryDto created = entryService.create(req, userId);
                createdCount++;
                String catName = created.getCategoryName() != null ? created.getCategoryName() : "";
                String sign = "INCOME".equals(type) ? "+" : "-";
                savedLines.add(String.format("%s %s • %s", sign, responseGenerator.formatVnd(slot.getAmount(), language), catName));
            } catch (Exception ex) {
                log.error("Failed to create entry: {}", req, ex);
                errors.add(ex.getMessage());
            }
        }

        if (createdCount == 0) {
            String errorText = errors.isEmpty() ? responseGenerator.insertFailed(null, language) : responseGenerator.insertFailed(errors.get(0), language);
            return AiAssistantResponse.builder().intent("INSERT").refreshRequired(false).reply(errorText).build();
        }

        return AiAssistantResponse.builder()
                .intent("INSERT")
                .createdCount(createdCount)
                .refreshRequired(true)
                .reply(responseGenerator.insertSuccess(createdCount, savedLines, language))
                .build();
    }

    private boolean isSameIdentity(GeminiParsedEntry a, GeminiParsedEntry b) {
        if (a == null || b == null) return false;
        String noteA = (a.note != null ? a.note.trim() : "").toLowerCase(Locale.ROOT);
        String noteB = (b.note != null ? b.note.trim() : "").toLowerCase(Locale.ROOT);
        String catA = (a.categoryName != null ? a.categoryName.trim() : "").toLowerCase(Locale.ROOT);
        String catB = (b.categoryName != null ? b.categoryName.trim() : "").toLowerCase(Locale.ROOT);
        
        // 1. Khớp tuyệt đối (Exact Match)
        if (noteA.equals(noteB) && catA.equals(catB)) {
            return true;
        }

        // 2. Khớp theo "Sửa/Thay thế" (Correction Match)
        // Nếu cùng danh mục và người dùng có ý định sửa đổi một mục hiện có.
        if (catA.equals(catB) && !catA.isEmpty()) {
            // Danh sách các động từ gợi ý việc sửa đổi (Tiếng Việt và Tiếng Anh)
            List<String> correctionVerbs = List.of("sua", "thay", "doi", "fix", "edit", "change", "correct", "update");
            String normalizedNoteA = textPreprocessor.normalizeVietnamese(noteA);
            boolean isCorrection = correctionVerbs.stream().anyMatch(normalizedNoteA::contains);
            
            if (isCorrection) {
                // Kiểm tra sự trùng lặp từ khóa giữa note cũ và note mới
                // Ví dụ: note cũ là "Đi taxi", note mới là "Sửa cái taxi đó" -> chung từ "taxi"
                Set<String> keywordsB = extractSignificantKeywords(noteB);
                if (keywordsB.isEmpty() || keywordsB.stream().anyMatch(noteA::contains)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    private Set<String> extractSignificantKeywords(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        String normalized = textPreprocessor.normalizeVietnamese(text).toLowerCase(Locale.ROOT);
        String[] words = normalized.split("\\s+");
        Set<String> keywords = new HashSet<>();
        List<String> stopWords = List.of("cho", "het", "la", "bi", "duoc", "cai", "nay", "vua", "the", "for", "with");
        
        for (String w : words) {
            String cleaned = w.replaceAll("[^a-z0-9]", "");
            if (cleaned.length() >= 3 && !stopWords.contains(cleaned)) {
                keywords.add(cleaned);
            }
        }
        return keywords;
    }
}
