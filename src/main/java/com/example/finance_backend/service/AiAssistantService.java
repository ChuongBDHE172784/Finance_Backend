package com.example.finance_backend.service;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.service.ai.*;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.assistant.intent.IntentHandler;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import com.example.finance_backend.service.assistant.storage.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service trung tâm điều phối toàn bộ luồng xử lý của Trợ lý ảo AI.
 * Nhiệm vụ: Tiền xử lý, gọi Gemini NLU, quản lý trạng thái và định tuyến tới các Handler chuyên biệt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAssistantService {

    private final TextPreprocessor textPreprocessor;
    private final GeminiClientWrapper geminiClient;
    private final ResponseGenerator responseGenerator;
    private final AiMessageRepository aiMessageRepository;
    private final com.example.finance_backend.repository.UserRepository userRepository;
    private final KeywordCleaner keywordCleaner;
    
    // Quản lý hạ tầng (Lưu trữ ảnh, Trạng thái hội thoại)
    private final FileStorageService fileStorageService;
    private final ConversationStateService stateService;
    
    // Danh sách các xử lý viên cho từng ý định (Intent)
    private final List<IntentHandler> handlers;
    private final Map<Intent, IntentHandler> handlerMap = new EnumMap<>(Intent.class);

    /**
     * Khởi tạo bản đồ ánh xạ từ Intent sang Handler tương ứng.
     */
    @PostConstruct
    public void init() {
        for (IntentHandler handler : handlers) {
            for (Intent intent : handler.getSupportedIntents()) {
                handlerMap.put(intent, handler);
            }
        }
    }

    /**
     * Phương thức bao bọc xử lý chính, đảm bảo không bị crash và trả về lỗi thân thiện.
     */
    public AiAssistantResponse handle(AiAssistantRequest request) {
        try {
            return doHandle(request);
        } catch (Throwable t) {
            log.error("Unhandled error in AiAssistantService: ", t);
            String language = request.getLanguage() != null ? request.getLanguage() : "vi";
            return AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(responseGenerator.aiConnectionError(language))
                    .build();
        }
    }

    /**
     * Luồng xử lý chính (Pipeline): Preprocess -> Gemini -> Intent Detection -> Routing -> Finalize.
     */
    private AiAssistantResponse doHandle(AiAssistantRequest request) {
        final String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String conversationId = request.getConversationId();
        // Tạo ID hội thoại mới nếu chưa có
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        String language = request.getLanguage() != null ? request.getLanguage() : "vi";
        // Bước 1: Tiền xử lý văn bản
        ParsedMessage parsed = textPreprocessor.preprocess(message, language);

        // Bước 1b: Xử lý đầu vào trống
        if (message.isEmpty() && (request.getBase64Image() == null || request.getBase64Image().isBlank())) {
            AiAssistantResponse resp = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(responseGenerator.emptyInputMessage(language))
                    .build();
            resp.setConversationId(conversationId);
            return resp;
        }

        // Bước 2: Nạp lịch sử (Giới hạn 6 tin nhắn để tiết kiệm token và tránh limit API)
        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (history.size() > 6) {
            history = history.subList(history.size() - 6, history.size());
        }

        // Bước 2.5: Xử lý yêu cầu Hủy bỏ
        if (keywordCleaner.isCancelRequest(parsed.getNormalizedText())) {
            stateService.clearAllStates(conversationId);
            AiAssistantResponse resp = AiAssistantResponse.builder()
                    .intent("CANCEL")
                    .reply(responseGenerator.t(language, "Đã hủy bỏ thao tác.", "Operation cancelled."))
                    .build();
            resp.setConversationId(conversationId);
            return finalizeResponse(resp, conversationId, message, null, request.getUserId());
        }

        // Bước 3: Lưu trữ ảnh nếu có
        String imagePath = fileStorageService.saveBase64Image(request.getBase64Image());

        // Bước 4: Gọi Gemini NLU (Agentic với Function Calling để bóc tách thông tin)
        String customApiKey = userRepository.findById(request.getUserId()).map(u -> u.getGeminiApiKey()).orElse(null);
        GeminiParseResult geminiResult = null;
        try {
            geminiResult = geminiClient.parse(message, request.getBase64Image(), history, language, customApiKey);
        } catch (Throwable t) {
            log.error("Gemini Agent parse failed: {}", t.getMessage());
        }

        // Bước 5: Kiểm tra giới hạn tần suất gọi AI
        if (geminiResult != null && geminiResult.isRateLimited) {
            AiAssistantResponse resp = AiAssistantResponse.builder()
                        .intent("AI_BUSY")
                        .reply(responseGenerator.aiBusyMessage(language))
                        .build();
            resp.setConversationId(conversationId);
            return resp;
        }

        // Bước 6: Xác định Ý định (Intent) từ kết quả AI
        IntentResult intentResult = detectIntent(geminiResult, request.getBase64Image() != null);

        // Bước 7: Quản lý chuyển đổi trạng thái (Xóa trạng thái cũ nếu có ý định mới hoàn toàn)
        handleStateTransitions(conversationId, intentResult);

        // Bước 8: Tìm và gọi Handler tương ứng
        Intent intent = intentResult.getIntent();
        IntentHandler handler = handlerMap.get(intent);
        if (handler == null) {
            handler = handlerMap.get(Intent.UNKNOWN);
        }

        AiAssistantResponse response = handler.handle(request, parsed, intentResult, geminiResult, history);
        response.setConversationId(conversationId);
        
        // Bước 9: Hoàn thiện (Lưu tin nhắn vào DB lịch sử)
        return finalizeResponse(response, conversationId, message, imagePath, request.getUserId());
    }

    /**
     * Xác định Intent dựa trên kết quả trả về từ Gemini hoặc logic mặc định khi có ảnh.
     */
    private IntentResult detectIntent(GeminiParseResult gemini, boolean hasImage) {
        if (gemini != null && gemini.intent != null && !"UNKNOWN".equalsIgnoreCase(gemini.intent)) {
            return mapGeminiIntent(gemini.intent);
        }
        
        // Nếu AI không nhận diện được nhưng có ảnh, mặc định là INSERT (có thể là hóa đơn)
        if (hasImage) {
            return IntentResult.builder().intent(Intent.INSERT_TRANSACTION).confidence(0.7).source(IntentResult.Source.GEMINI).build();
        }

        return IntentResult.builder().intent(Intent.UNKNOWN).confidence(0.0).source(IntentResult.Source.GEMINI).build();
    }

    /**
     * Xóa các trạng thái thừa (như bản nháp đang nhập dở) khi người dùng chuyển sang ý định khác.
     */
    private void handleStateTransitions(String conversationId, IntentResult intentResult) {
        if (intentResult.getConfidence() >= 0.8 && intentResult.getIntent() != Intent.INSERT_TRANSACTION && intentResult.getIntent() != Intent.UNKNOWN) {
            stateService.clearAllStates(conversationId);
        }
    }

    /**
     * Lưu tin nhắn của User và Server vào DB để làm dữ liệu ngữ cảnh cho tương lai.
     */
    private AiAssistantResponse finalizeResponse(AiAssistantResponse response, String conversationId, String userMsg, String imagePath, Long userId) {
        if (response.getIntent() == null) response.setIntent("UNKNOWN");
        
        // Lưu tin nhắn User
        AiMessage userAiMsg = AiMessage.builder()
                .conversationId(conversationId)
                .role("USER")
                .content(userMsg)
                .imageUrl(imagePath)
                .userId(userId)
                .build();
        aiMessageRepository.save(userAiMsg);

        // Lưu phản hồi của Assistant
        AiMessage assistantAiMsg = AiMessage.builder()
                .conversationId(conversationId)
                .role("ASSISTANT")
                .content(response.getReply())
                .userId(userId)
                .build();
        aiMessageRepository.save(assistantAiMsg);

        return response;
    }

    /**
     * Ánh xạ chuỗi định danh Intent từ Gemini sang Enum nội bộ.
     */
    private IntentResult mapGeminiIntent(String intentStr) {
        Intent intent = switch (intentStr.toUpperCase()) {
            case "INSERT", "INSERT_TRANSACTION" -> Intent.INSERT_TRANSACTION;
            case "QUERY", "QUERY_TRANSACTION" -> Intent.QUERY_TRANSACTION;
            case "UPDATE", "UPDATE_TRANSACTION" -> Intent.UPDATE_TRANSACTION;
            case "DELETE", "DELETE_TRANSACTION" -> Intent.DELETE_TRANSACTION;
            case "BUDGET", "CREATE_BUDGET" -> Intent.CREATE_BUDGET;
            case "INCOME_GOAL", "CREATE_INCOME_GOAL" -> Intent.CREATE_INCOME_GOAL;
            case "ADVICE", "FINANCIAL_ADVICE", "GETFINANCIALADVICE" -> Intent.FINANCIAL_ADVICE;
            case "REPORT", "MONTHLY_SUMMARY", "GETMONTHLYSUMMARY" -> Intent.MONTHLY_SUMMARY;
            case "ANALYTICS", "BUDGET_QUERY" -> Intent.BUDGET_QUERY;
            case "SCHEDULE", "CREATE_SCHEDULE" -> Intent.CREATE_SCHEDULE;
            case "DISABLE_SCHEDULE" -> Intent.DISABLE_SCHEDULE;
            case "ENABLE_SCHEDULE" -> Intent.ENABLE_SCHEDULE;
            case "DELETE_SCHEDULE" -> Intent.DELETE_SCHEDULE;
            case "LIST_SCHEDULES" -> Intent.LIST_SCHEDULES;
            case "LIST_UPCOMING_TRANSACTIONS" -> Intent.LIST_UPCOMING_TRANSACTIONS;
            case "EXPLAIN_TRANSACTION_SOURCE" -> Intent.EXPLAIN_TRANSACTION_SOURCE;
            case "FINANCIAL_SCORE", "GETFINANCIALSCORE" -> Intent.FINANCIAL_SCORE;
            case "CHAT", "GENERAL_CHAT" -> Intent.GENERAL_CHAT;
            default -> Intent.UNKNOWN;
        };
        return IntentResult.builder().intent(intent).confidence(1.0).source(IntentResult.Source.GEMINI).build();
    }

    /**
     * Lấy toàn bộ lịch sử nhắn tin của một người dùng.
     */
    public List<AiMessage> getHistory(Long userId) {
        return aiMessageRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    /**
     * Xóa sạch lịch sử chat và các file ảnh liên quan của người dùng.
     */
    @Transactional
    public void clearHistory(Long userId) {
        log.info("Clearing AI history for user: {}", userId);
        List<AiMessage> messages = aiMessageRepository.findByUserIdOrderByCreatedAtAsc(userId);
        
        // Xóa từng file ảnh vật lý trên bộ nhớ
        for (AiMessage m : messages) {
            if (m.getImageUrl() != null && !m.getImageUrl().isBlank()) {
                fileStorageService.deleteFile(m.getImageUrl());
            }
        }
        
        // Xóa các bản ghi tin nhắn trong DB
        aiMessageRepository.deleteAll(messages);
        
        // Xóa các trạng thái trong bộ nhớ (Drafts, Plans...)
        messages.stream()
                .map(AiMessage::getConversationId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(stateService::clearAllStates);
    }

    /**
     * Cập nhật API Key cá nhân.
     */
    @Transactional
    public void saveCustomApiKey(Long userId, String apiKey) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setGeminiApiKey(apiKey);
            userRepository.save(u);
        });
    }

    /**
     * Xóa API Key cá nhân.
     */
    @Transactional
    public void deleteCustomApiKey(Long userId) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setGeminiApiKey(null);
            userRepository.save(u);
        });
    }

    /**
     * Kiểm tra sự tồn tại của API Key cá nhân.
     */
    public boolean hasCustomApiKey(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getGeminiApiKey() != null && !u.getGeminiApiKey().isBlank())
                .orElse(false);
    }
}
