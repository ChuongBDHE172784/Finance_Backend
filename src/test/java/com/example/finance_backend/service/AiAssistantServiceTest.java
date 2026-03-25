package com.example.finance_backend.service;

import com.example.finance_backend.dto.AiAssistantRequest;
import com.example.finance_backend.dto.AiAssistantResponse;
import com.example.finance_backend.dto.IntentResult;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.service.ai.GeminiClientWrapper;
import com.example.finance_backend.service.ai.IntentDetector;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.intent.IntentHandler;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import com.example.finance_backend.service.assistant.storage.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantServiceTest {

    @Mock
    private AiMessageRepository aiMessageRepository;
    @Mock
    private GeminiClientWrapper geminiClient;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private ConversationStateService stateService;
    @Mock
    private IntentDetector intentDetector;
    @Mock
    private IntentHandler intentHandler;

    private AiAssistantService aiAssistantService;
    private TextPreprocessor textPreprocessor = new TextPreprocessor();
    private ResponseGenerator responseGenerator = new ResponseGenerator();

    @BeforeEach
    void setUp() {
        // Mock a handler that supports UNKNOWN intent
        when(intentHandler.getSupportedIntents()).thenReturn(List.of(IntentResult.Intent.UNKNOWN));
        
        aiAssistantService = new AiAssistantService(
                textPreprocessor,
                intentDetector,
                geminiClient,
                responseGenerator,
                aiMessageRepository,
                fileStorageService,
                stateService,
                List.of(intentHandler)
        );
        aiAssistantService.init();
    }

    @Test
    void testEmptyMessage_ReturnsUnknown() {
        AiAssistantRequest request = AiAssistantRequest.builder()
                .message("")
                .build();

        AiAssistantResponse response = aiAssistantService.handle(request);
        assertEquals("UNKNOWN", response.getIntent());
        assertNotNull(response.getReply());
        assertNotNull(response.getConversationId());
    }

    @Test
    void testHandle_RoutesToCorrectHandler() {
        String message = "test message";
        String conversationId = UUID.randomUUID().toString();
        AiAssistantRequest request = AiAssistantRequest.builder()
                .message(message)
                .conversationId(conversationId)
                .build();

        IntentResult intentResult = IntentResult.builder()
                .intent(IntentResult.Intent.UNKNOWN)
                .confidence(1.0)
                .build();

        when(aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(new ArrayList<>());
        when(intentDetector.detect(any())).thenReturn(intentResult);
        
        AiAssistantResponse mockResponse = AiAssistantResponse.builder()
                .intent("UNKNOWN")
                .reply("Mock Reply")
                .build();
                
        when(intentHandler.handle(eq(request), any(), eq(intentResult), any(), any()))
                .thenReturn(mockResponse);

        AiAssistantResponse response = aiAssistantService.handle(request);
        assertEquals("UNKNOWN", response.getIntent());
        assertEquals("Mock Reply", response.getReply());
        verify(aiMessageRepository, times(2)).save(any());
    }
}
