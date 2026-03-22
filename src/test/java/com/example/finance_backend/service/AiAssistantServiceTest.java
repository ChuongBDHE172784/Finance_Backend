package com.example.finance_backend.service;

import com.example.finance_backend.dto.AiAssistantRequest;
import com.example.finance_backend.dto.AiAssistantResponse;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.repository.BudgetRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.ai.ConversationContextManager;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.FinancialScoreEngine;
import com.example.finance_backend.service.ai.GeminiClientWrapper;
import com.example.finance_backend.service.ai.IntentDetector;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.SpendingAnalyticsService;
import com.example.finance_backend.service.ai.TextPreprocessor;
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
    @Mock private BudgetRepository budgetRepository;
    @Mock private com.example.finance_backend.repository.CategoryRepository categoryRepository;

    private AiAssistantService aiAssistantService;

    @BeforeEach
    void setUp() {
        TextPreprocessor textPreprocessor = new TextPreprocessor();
        IntentDetector intentDetector = new IntentDetector();
        EntityExtractor entityExtractor = new EntityExtractor(textPreprocessor);
        ConversationContextManager contextManager = new ConversationContextManager(textPreprocessor, entityExtractor);
        GeminiClientWrapper geminiClient = mock(GeminiClientWrapper.class);
        ResponseGenerator responseGenerator = new ResponseGenerator();
        SpendingAnalyticsService analyticsService = new SpendingAnalyticsService(entryRepository, categoryService, budgetRepository, categoryRepository);
        FinancialScoreEngine scoreEngine = new FinancialScoreEngine(entryRepository, budgetRepository, analyticsService);

        aiAssistantService = new AiAssistantService(
                textPreprocessor, intentDetector, entityExtractor,
                contextManager, geminiClient, responseGenerator, analyticsService,
                scoreEngine,
                entryService, entryRepository, accountRepository,
                aiMessageRepository, categoryService, budgetRepository, categoryRepository);
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
    void testInsert_ReturnsDraft() {
        when(aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(new ArrayList<>());

        AiAssistantRequest request = AiAssistantRequest.builder()
                .message("Ăn phở 45k")
                .conversationId("test-conv")
                .userId(1L)
                .build();

        AiAssistantResponse response = aiAssistantService.handle(request);
        assertTrue(response.getIsDraft());
        assertNotNull(response.getEntries());
        assertEquals(1, response.getEntries().size());
        assertTrue(response.getReply().contains("kiểm tra lại") || response.getReply().contains("review"));
    }

    @Test
    void testInsert_SavesOnConfirmation() {
        // Setup mock Gemini to return confirmation
        GeminiClientWrapper.GeminiParseResult gemini = new GeminiClientWrapper.GeminiParseResult();
        gemini.intent = "INSERT";
        gemini.isConfirmation = true;
        GeminiClientWrapper.GeminiParsedEntry geminiEntry = new GeminiClientWrapper.GeminiParsedEntry();
        geminiEntry.amount = new java.math.BigDecimal("45000");
        geminiEntry.categoryName = "Ăn uống";
        gemini.entries = List.of(geminiEntry);

        // Access the geminiClient mock created in BeforeEach via reflection or just use the one we know is there.
        // Actually, we can just use the 'geminiClient' field if we had one. 
        // Let's re-mock it and inject it.
        GeminiClientWrapper geminiClient = mock(GeminiClientWrapper.class);
        when(geminiClient.parse(anyString(), any(), any(), anyString())).thenReturn(gemini);
        
        // Use a real context manager and other components as in setUp
        TextPreprocessor tp = new TextPreprocessor();
        EntityExtractor ee = new EntityExtractor(tp);
        aiAssistantService = new AiAssistantService(
                tp, new IntentDetector(), ee,
                new ConversationContextManager(tp, ee), geminiClient, new ResponseGenerator(), 
                mock(SpendingAnalyticsService.class), mock(FinancialScoreEngine.class),
                entryService, entryRepository, accountRepository,
                aiMessageRepository, categoryService, budgetRepository, categoryRepository);

        when(aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(new ArrayList<>());
        
        // Mock account resolution
        com.example.finance_backend.entity.Account account = com.example.finance_backend.entity.Account.builder().id(1L).userId(1L).build();
        when(accountRepository.findByUserIdOrderByNameAsc(any())).thenReturn(List.of(account));
        when(categoryService.getIdToNameMap()).thenReturn(java.util.Map.of(1L, "Ăn uống"));

        // Mock entry service
        com.example.finance_backend.dto.FinancialEntryDto dto = new com.example.finance_backend.dto.FinancialEntryDto();
        dto.setCategoryName("Ăn uống");
        when(entryService.create(any(), any())).thenReturn(dto);

        AiAssistantRequest request = AiAssistantRequest.builder()
                .message("Lưu đi")
                .conversationId("test-conv")
                .userId(1L)
                .build();

        AiAssistantResponse response = aiAssistantService.handle(request);
        
        assertFalse(Boolean.TRUE.equals(response.getIsDraft()));
        assertEquals(1, response.getCreatedCount());
        assertTrue(response.getReply().contains("Đã lưu") || response.getReply().contains("Saved"));
        verify(entryService, times(1)).create(any(), any());
    }
}
