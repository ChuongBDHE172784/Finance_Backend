package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.dto.IntentResult.Intent;

import java.util.List;

public interface IntentHandler {

    List<Intent> getSupportedIntents();

    AiAssistantResponse handle(
            AiAssistantRequest request,
            ParsedMessage parsedMessage,
            IntentResult intentResult,
            GeminiParseResult geminiResult,
            List<AiMessage> history);
}
