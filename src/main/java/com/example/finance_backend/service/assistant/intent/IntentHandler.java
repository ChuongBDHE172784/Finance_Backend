package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.dto.IntentResult.Intent;

import java.util.List;

/**
 * Giao diện (Interface) chung cho mọi Intent Handler trong hệ thống AI.
 * Mỗi Handler sẽ chịu trách nhiệm xử lý một hoặc nhiều ý định (Intent) cụ thể.
 */
public interface IntentHandler {

    /** Trả về danh sách các Intent mà Handler này có thể xử lý. */
    List<Intent> getSupportedIntents();

    /**
     * Phương thức xử lý chính cho Intent.
     * @param request Yêu cầu ban đầu từ người dùng.
     * @param parsedMessage Tin nhắn đã qua tiền xử lý.
     * @param intentResult Kết quả xác định Intent.
     * @param geminiResult Dữ liệu bóc tách từ Gemini.
     * @param history Lịch sử trò chuyện.
     * @return AiAssistantResponse Phản hồi trả về cho client.
     */
    AiAssistantResponse handle(
            AiAssistantRequest request,
            ParsedMessage parsedMessage,
            IntentResult intentResult,
            GeminiParseResult geminiResult,
            List<AiMessage> history);
}
