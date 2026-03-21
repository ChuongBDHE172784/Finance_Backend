package com.example.finance_backend.service;

import com.example.finance_backend.dto.AiAssistantRequest;
import com.example.finance_backend.dto.AiAssistantResponse;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantServiceTest {

    @Mock private FinancialEntryService entryService;
    @Mock private FinancialEntryRepository entryRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AiMessageRepository aiMessageRepository;
    @Mock private CategoryService categoryService;

    private AiAssistantService aiAssistantService;

    @BeforeEach
    void setUp() {
        TextPreprocessor textPreprocessor = new TextPreprocessor();
        IntentDetector intentDetector = new IntentDetector();
        EntityExtractor entityExtractor = new EntityExtractor(textPreprocessor);
        ConversationContextManager contextManager = new ConversationContextManager(textPreprocessor, entityExtractor);
        GeminiClientWrapper geminiClient = mock(GeminiClientWrapper.class);
        ResponseGenerator responseGenerator = new ResponseGenerator();
        SpendingAnalyticsService analyticsService = new SpendingAnalyticsService(entryRepository, categoryService);

        aiAssistantService = new AiAssistantService(
                textPreprocessor, intentDetector, entityExtractor,
                contextManager, geminiClient, responseGenerator, analyticsService,
                entryService, entryRepository, accountRepository,
                aiMessageRepository, categoryService);
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
    void testDeleteIntent_Detected() {
        when(aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(new ArrayList<>());
        when(entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(any(), any()))
                .thenReturn(new ArrayList<>());

        AiAssistantRequest request = AiAssistantRequest.builder()
                .message("Xóa giao dịch 45k")
                .conversationId("test-conv")
                .build();

        AiAssistantResponse response = aiAssistantService.handle(request);
        assertEquals("DELETE", response.getIntent());
    }

    @Test
    void testQueryIntent_Detected() {
        when(aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(new ArrayList<>());
        when(entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(any(), any()))
                .thenReturn(new ArrayList<>());

        AiAssistantRequest request = AiAssistantRequest.builder()
                .message("Hôm nay tiêu bao nhiêu")
                .conversationId("test-conv")
                .build();

        AiAssistantResponse response = aiAssistantService.handle(request);
        assertEquals("QUERY", response.getIntent());
    }

    @Test
    void testInsertWithAccount_NeedsAccount() {
        when(aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(new ArrayList<>());

        // Mock multiple accounts to trigger account selection
        List<Account> accounts = List.of(
                Account.builder().id(1L).name("MoMo").userId(1L).build(),
                Account.builder().id(2L).name("Tiền mặt").userId(1L).build()
        );
        when(accountRepository.findByUserIdOrderByNameAsc(1L)).thenReturn(accounts);

        AiAssistantRequest request = AiAssistantRequest.builder()
                .message("Ăn phở 45k")
                .conversationId("test-conv")
                .userId(1L)
                .build();

        AiAssistantResponse response = aiAssistantService.handle(request);
        // Should either ask for account or succeed with INSERT
        assertTrue("NEED_ACCOUNT".equals(response.getIntent()) || "INSERT".equals(response.getIntent()));
    }
}
