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
/**
 * Lớp trừu tượng cơ sở (Base Class) cung cấp các phương thức tiện ích 
 * cho việc xử lý Intent trong AI Assistant.
 */
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

    /** Tạo bản đồ ánh xạ từ tên hạng mục (đã chuẩn hóa) sang ID. */
    protected Map<String, Long> getNameToIdMap() {
        return categoryService.findAll().stream()
                .collect(Collectors.toMap(c -> textPreprocessor.normalizeVietnamese(c.getName()), c -> c.getId(),
                        (a, b) -> a));
    }

    /** Chuyển tên hạng mục do AI bóc tách thành ID tương ứng trong Database. */
    protected Long resolveCategoryId(Map<String, Long> nameToId, String name, Long fallbackId) {
        if (name == null || name.isBlank())
            return fallbackId;
        String norm = textPreprocessor.normalizeVietnamese(name);
        return nameToId.getOrDefault(norm, fallbackId);
    }

    /** Lấy ID của hạng mục 'Khác' nếu không tìm thấy hạng mục khớp. */
    protected Long resolveFallbackCategoryId(Map<String, Long> nameToId) {
        return nameToId.getOrDefault("khac", null);
    }

    /** Tạo chuỗi văn bản danh sách các hạng mục hiện có cho AI gợi ý. */
    protected String getCategoryListResponse(EntryType type, String language) {
        String cats = categoryService.findAll().stream()
                .filter(c -> c.getType() == type)
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));
        return "\n" + responseGenerator.t(language, "Danh mục gợi ý: ", "Suggested categories: ") + cats;
    }

    /** Xác định tài khoản/ví nào mà người dùng muốn thực hiện giao dịch. */
    protected AccountResolution resolveAccount(String message, Long defaultAccountId, Long userId, String language) {
        // Nếu đã có ID tài khoản mặc định, ưu tiên sử dụng
        if (defaultAccountId != null)
            return new AccountResolution(defaultAccountId, false, null);
        // Ngược lại cần người dùng chọn tài khoản
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
