package com.example.finance_backend.controller;

import com.example.finance_backend.dto.AiAssistantRequest;
import com.example.finance_backend.dto.AiAssistantResponse;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.service.AiAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller xử lý các yêu cầu liên quan đến Trợ lý ảo AI.
 * Cung cấp các endpoint cho chat, quản lý lịch sử và cấu hình API Key.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    /**
     * Endpoint chính để tương tác với Trợ lý ảo.
     * 
     * @param request Chứa tin nhắn văn bản, ảnh (nếu có) và ngôn ngữ.
     * @param userId  ID của người dùng được gửi qua Header.
     * @return Phản hồi của AI bao gồm nội dung trả lời và ý định (intent) được phân
     *         tích.
     */
    @PostMapping("/assistant")
    public ResponseEntity<AiAssistantResponse> assistant(
            @Valid @RequestBody AiAssistantRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        request.setUserId(userId);
        return ResponseEntity.ok(aiAssistantService.handle(request));
    }

    /**
     * Lấy lịch sử tất cả các tin nhắn chat của người dùng.
     * 
     * @param userId ID của người dùng.
     * @return Danh sách các tin nhắn đã gửi và nhận trước đó.
     */
    @GetMapping("/history")
    public ResponseEntity<List<AiMessage>> getHistory(
            @RequestHeader(value = "X-User-Id") Long userId) {
        return ResponseEntity.ok(aiAssistantService.getHistory(userId));
    }

    /**
     * Xóa sạch lịch sử chat của người dùng.
     * 
     * @param userId ID của người dùng.
     */
    @DeleteMapping("/history")
    public ResponseEntity<Void> deleteHistory(
            @RequestHeader(value = "X-User-Id") Long userId) {
        aiAssistantService.clearHistory(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lưu API Key Gemini riêng cho người dùng.
     * Nếu không có, hệ thống sẽ dùng key mặc định của hệ thống.
     */
    @PostMapping("/config/key")
    public ResponseEntity<Void> saveApiKey(
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestBody java.util.Map<String, String> body) {
        String key = body.get("apiKey");
        aiAssistantService.saveCustomApiKey(userId, key);
        return ResponseEntity.ok().build();
    }

    /**
     * Xóa API Key cá nhân của người dùng.
     */
    @DeleteMapping("/config/key")
    public ResponseEntity<Void> deleteApiKey(
            @RequestHeader(value = "X-User-Id") Long userId) {
        aiAssistantService.deleteCustomApiKey(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Kiểm tra xem người dùng đã thiết lập API Key riêng hay chưa.
     */
    @GetMapping("/config/key/status")
    public ResponseEntity<java.util.Map<String, Boolean>> getApiKeyStatus(
            @RequestHeader(value = "X-User-Id") Long userId) {
        boolean hasKey = aiAssistantService.hasCustomApiKey(userId);
        return ResponseEntity.ok(java.util.Map.of("hasCustomKey", hasKey));
    }
}
