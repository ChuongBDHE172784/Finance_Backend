package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ai.FinancialScoreEngine;
import com.example.finance_backend.service.ai.FinancialScoreEngine.FinancialScoreResult;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Handler xử lý các yêu cầu tư vấn tài chính, tính điểm sức khỏe tài chính 
 * và các cuộc hội thoại thông thường (General Chat).
 */
@Component
public class AdviceHandler extends BaseIntentHandler {

    private final FinancialScoreEngine scoreEngine;

    public AdviceHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            FinancialScoreEngine scoreEngine) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.scoreEngine = scoreEngine;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.FINANCIAL_ADVICE, Intent.FINANCIAL_SCORE, Intent.GENERAL_CHAT, Intent.UNKNOWN);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String language = request.getLanguage();
        Long userId = request.getUserId();
        Intent intent = intentResult.getIntent();

        if (Intent.FINANCIAL_ADVICE.equals(intent) || Intent.FINANCIAL_SCORE.equals(intent)) {
            return handleFinancialAdvice(userId, language);
        }
        
        // General chat logic
        String reply = null;
        if (gemini != null && gemini.adviceReply != null && !gemini.adviceReply.isBlank()) {
            reply = gemini.adviceReply;
        } else if (gemini == null && parsed.getOriginalText() != null && !parsed.getOriginalText().isBlank()) {
            // Fallback to Gemini text generation only if gemini was NOT tried or failed with non-429
            // But since AiAssistantService always calls it, gemini==null means it already failed once.
            // We'll only try again if we really want to be resilient, but for now let's avoid redundant calls.
            // reply = responseGenerator.unknownMessage(language); 
        }
        
        if (reply == null || reply.isBlank()) {
            reply = responseGenerator.unknownMessage(language);
        }

        return AiAssistantResponse.builder()
                .intent("GENERAL")
                .reply(reply)
                .build();
    }

    private AiAssistantResponse handleFinancialAdvice(Long userId, String language) {
        LocalDate now = LocalDate.now();
        FinancialScoreResult score = scoreEngine.computeScore(userId, now.getMonthValue(), now.getYear());
        return AiAssistantResponse.builder()
                .intent("ADVICE")
                .reply(responseGenerator.financialScoreReply(score, language))
                .build();
    }
}
