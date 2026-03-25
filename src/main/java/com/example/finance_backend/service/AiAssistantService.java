package com.example.finance_backend.service;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.service.ai.*;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.assistant.intent.IntentHandler;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import com.example.finance_backend.service.assistant.storage.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAssistantService {

    private final TextPreprocessor textPreprocessor;
    private final IntentDetector intentDetector;
    private final GeminiClientWrapper geminiClient;
    private final ResponseGenerator responseGenerator;
    private final AiMessageRepository aiMessageRepository;
    
    // Infrastructure
    private final FileStorageService fileStorageService;
    private final ConversationStateService stateService;
    
    // Handlers
    private final List<IntentHandler> handlers;
    private final Map<Intent, IntentHandler> handlerMap = new EnumMap<>(Intent.class);

    @PostConstruct
    public void init() {
        for (IntentHandler handler : handlers) {
            for (Intent intent : handler.getSupportedIntents()) {
                handlerMap.put(intent, handler);
            }
        }
    }

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

    private AiAssistantResponse doHandle(AiAssistantRequest request) {
        final String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        String language = request.getLanguage() != null ? request.getLanguage() : "vi";
        ParsedMessage parsed = textPreprocessor.preprocess(message, language);

        // 1. Handle empty input
        if (message.isEmpty() && (request.getBase64Image() == null || request.getBase64Image().isBlank())) {
            AiAssistantResponse resp = AiAssistantResponse.builder()
                    .intent("UNKNOWN")
                    .reply(responseGenerator.emptyInputMessage(language))
                    .build();
            resp.setConversationId(conversationId);
            return resp;
        }

        // 2. Load History
        List<AiMessage> history = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        // 3. Process image if exists
        String imagePath = fileStorageService.saveBase64Image(request.getBase64Image());

        // 4. Gemini NLU
        GeminiParseResult geminiResult = null;
        try {
            geminiResult = geminiClient.parse(message, request.getBase64Image(), history, language);
        } catch (Throwable t) {
            log.warn("Gemini parse CRASHED, falling back to rules: {}", t.getMessage());
        }

        // 5. Handle Rate Limiting
        if (geminiResult != null && geminiResult.isRateLimited) {
            // If it's an image, we MUST have Gemini, so return early.
            // If it's just text, we can try to fall back to rules.
            if (request.getBase64Image() != null && !request.getBase64Image().isBlank()) {
                AiAssistantResponse resp = AiAssistantResponse.builder()
                        .intent("AI_BUSY")
                        .reply(responseGenerator.aiBusyMessage(language))
                        .build();
                resp.setConversationId(conversationId);
                return resp;
            }
            log.warn("Gemini is rate limited, falling back to rules for text-only message.");
        }

        // 6. Intent Detection
        IntentResult intentResult = detectIntent(parsed, geminiResult);

        // 7. State Management & Transitions
        handleStateTransitions(conversationId, intentResult);

        // 7. Route to Handler
        Intent intent = intentResult.getIntent();
        IntentHandler handler = handlerMap.get(intent);
        if (handler == null) {
            handler = handlerMap.get(Intent.UNKNOWN);
        }

        AiAssistantResponse response = handler.handle(request, parsed, intentResult, geminiResult, history);
        response.setConversationId(conversationId);
        
        // 8. Finalize (Save Messages)
        return finalizeResponse(response, conversationId, message, imagePath, request.getUserId());
    }

    private IntentResult detectIntent(ParsedMessage parsed, GeminiParseResult gemini) {
        if (gemini != null && gemini.intent != null && !gemini.intent.isBlank() && !"UNKNOWN".equalsIgnoreCase(gemini.intent)) {
            return mapGeminiIntent(gemini.intent);
        }
        if (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) {
            return IntentResult.builder().intent(Intent.INSERT_TRANSACTION).confidence(0.8).source(IntentResult.Source.GEMINI).build();
        }
        return intentDetector.detect(parsed);
    }

    private void handleStateTransitions(String conversationId, IntentResult intentResult) {
        if (intentResult.getConfidence() >= 0.8 && intentResult.getIntent() != Intent.INSERT_TRANSACTION && intentResult.getIntent() != Intent.UNKNOWN) {
            stateService.clearAllStates(conversationId);
        }
    }

    private AiAssistantResponse finalizeResponse(AiAssistantResponse response, String conversationId, String userMsg, String imagePath, Long userId) {
        if (response.getIntent() == null) response.setIntent("UNKNOWN");
        
        // Save User Message
        AiMessage userAiMsg = AiMessage.builder()
                .conversationId(conversationId)
                .role("USER")
                .content(userMsg)
                .imageUrl(imagePath)
                .userId(userId)
                .build();
        aiMessageRepository.save(userAiMsg);

        // Save Assistant Message
        AiMessage assistantAiMsg = AiMessage.builder()
                .conversationId(conversationId)
                .role("ASSISTANT")
                .content(response.getReply())
                .userId(userId)
                .build();
        aiMessageRepository.save(assistantAiMsg);

        return response;
    }

    private IntentResult mapGeminiIntent(String intentStr) {
        Intent intent = switch (intentStr.toUpperCase()) {
            case "INSERT", "INSERT_TRANSACTION" -> Intent.INSERT_TRANSACTION;
            case "QUERY", "QUERY_TRANSACTION" -> Intent.QUERY_TRANSACTION;
            case "UPDATE", "UPDATE_TRANSACTION" -> Intent.UPDATE_TRANSACTION;
            case "DELETE", "DELETE_TRANSACTION" -> Intent.DELETE_TRANSACTION;
            case "BUDGET", "CREATE_BUDGET" -> Intent.CREATE_BUDGET;
            case "INCOME_GOAL", "CREATE_INCOME_GOAL" -> Intent.CREATE_INCOME_GOAL;
            case "ADVICE", "FINANCIAL_ADVICE" -> Intent.FINANCIAL_ADVICE;
            case "REPORT", "MONTHLY_SUMMARY" -> Intent.MONTHLY_SUMMARY;
            case "ANALYTICS", "BUDGET_QUERY" -> Intent.BUDGET_QUERY;
            case "SCHEDULE", "CREATE_SCHEDULE" -> Intent.CREATE_SCHEDULE;
            case "CHAT", "GENERAL_CHAT" -> Intent.GENERAL_CHAT;
            default -> Intent.UNKNOWN;
        };
        return IntentResult.builder().intent(intent).confidence(1.0).source(IntentResult.Source.GEMINI).build();
    }

    public List<AiMessage> getHistory(Long userId) {
        return aiMessageRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    @Transactional
    public void clearHistory(Long userId) {
        log.info("Clearing AI history for user: {}", userId);
        List<AiMessage> messages = aiMessageRepository.findByUserIdOrderByCreatedAtAsc(userId);
        
        // Delete each associated image file from local storage
        for (AiMessage m : messages) {
            if (m.getImageUrl() != null && !m.getImageUrl().isBlank()) {
                fileStorageService.deleteFile(m.getImageUrl());
            }
        }
        
        // Delete all message records from database
        aiMessageRepository.deleteAll(messages);
        
        // Clear in-memory conversation states (Drafts, Plans, etc.)
        messages.stream()
                .map(AiMessage::getConversationId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(stateService::clearAllStates);
    }
}
