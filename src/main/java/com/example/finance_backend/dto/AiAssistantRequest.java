package com.example.finance_backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAssistantRequest {

    private String message;

    private String conversationId;

    private Long accountId;

    /** Language code from client (e.g., "vi", "en"). */
    private String language;

    /** User ID from X-User-Id header (for scoping entries/accounts). */
    private Long userId;

    /** Base64 encoded image for OCR/Visual analysis. */
    private String base64Image;
}
