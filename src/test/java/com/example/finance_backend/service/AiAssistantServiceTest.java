package com.example.finance_backend.service;

import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.AiMessageRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantServiceTest {

    @Mock
    private FinancialEntryService entryService;
    @Mock
    private FinancialEntryRepository entryRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AiMessageRepository aiMessageRepository;
    @Mock
    private CategoryService categoryService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AiAssistantService aiAssistantService;

    @BeforeEach
    void setUp() {
        // Any setup if needed
    }

    @Test
    void testParseFallbackUpdate_AmountOnly() {
        String message = "Sửa 200k thành 100k";
        List<AiMessage> history = new ArrayList<>();
        
        // Use reflection to call private method parseFallback
        Object result = ReflectionTestUtils.invokeMethod(aiAssistantService, "parseFallback", message, history);
        
        assertNotNull(result);
        
        // We'll check the fields via reflection or by making the inner class accessible if possible
        // But since they are private in AiAssistantService, we might need to use reflection for assertions too
        String intent = (String) ReflectionTestUtils.getField(result, "intent");
        assertEquals("UPDATE", intent);
        
        Object target = ReflectionTestUtils.getField(result, "target");
        assertNotNull(target);
        BigDecimal targetAmount = (BigDecimal) ReflectionTestUtils.getField(target, "amount");
        assertEquals(new BigDecimal("200000"), targetAmount);

        List<?> entries = (List<?>) ReflectionTestUtils.getField(result, "entries");
        assertNotNull(entries);
        assertFalse(entries.isEmpty());
        Object newEntry = entries.get(0);
        BigDecimal newAmount = (BigDecimal) ReflectionTestUtils.getField(newEntry, "amount");
        assertEquals(new BigDecimal("100000"), newAmount);
    }

    @Test
    void testParseFallbackUpdate_NoteAndAmount() {
        String message = "Sửa khoản đi siêu thị thành 100k";
        List<AiMessage> history = new ArrayList<>();
        
        Object result = ReflectionTestUtils.invokeMethod(aiAssistantService, "parseFallback", message, history);
        
        assertNotNull(result);
        String intent = (String) ReflectionTestUtils.getField(result, "intent");
        assertEquals("UPDATE", intent);
        
        Object target = ReflectionTestUtils.getField(result, "target");
        assertNotNull(target);
        String keywords = (String) ReflectionTestUtils.getField(target, "noteKeywords");
        assertTrue(keywords.contains("siêu thị") || keywords.contains("sieu thi"));

        List<?> entries = (List<?>) ReflectionTestUtils.getField(result, "entries");
        assertNotNull(entries);
        assertFalse(entries.isEmpty());
        Object newEntry = entries.get(0);
        BigDecimal newAmount = (BigDecimal) ReflectionTestUtils.getField(newEntry, "amount");
        assertEquals(new BigDecimal("100000"), newAmount);
    }

    @Test
    void testParseFallbackUpdate_NoteWithNoise() {
        String message = "Sửa khoản đi siêu thị 200k chiều nay thành 100k";
        List<AiMessage> history = new ArrayList<>();
        
        Object result = ReflectionTestUtils.invokeMethod(aiAssistantService, "parseFallback", message, history);
        
        assertNotNull(result);
        Object target = ReflectionTestUtils.getField(result, "target");
        assertNotNull(target);
        
        // Key logic to test: does the keyword cleaning in findTargetEntries work?
        // We can't call private findTargetEntries easily here with mocked repo without more setup,
        // but we can verify the extracted keywords here.
        String keywords = (String) ReflectionTestUtils.getField(target, "noteKeywords");
        
        // The keywords should ideally NOT contain "khoản", "200k", "chiều nay" AFTER handleUpdate/findTargetEntries cleans it.
        // However, parseFallback puts the raw part 0 into noteKeywords.
        // The cleaning happens INSIDE findTargetEntries.
        assertTrue(keywords.contains("đi siêu thị"));
        
        // Verify date was detected
        String targetDate = (String) ReflectionTestUtils.getField(target, "date");
        assertNotNull(targetDate);
        assertEquals(java.time.LocalDate.now().toString(), targetDate);
    }
}
