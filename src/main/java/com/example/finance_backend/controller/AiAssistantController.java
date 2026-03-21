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

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    @PostMapping("/assistant")
    public ResponseEntity<AiAssistantResponse> assistant(
            @Valid @RequestBody AiAssistantRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        request.setUserId(userId);
        return ResponseEntity.ok(aiAssistantService.handle(request));
    }

    @GetMapping("/history")
    public ResponseEntity<List<AiMessage>> getHistory(
            @RequestHeader(value = "X-User-Id") Long userId) {
        return ResponseEntity.ok(aiAssistantService.getHistory(userId));
    }
}
