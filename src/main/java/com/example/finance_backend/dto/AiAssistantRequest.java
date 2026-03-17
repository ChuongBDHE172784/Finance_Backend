package com.example.finance_backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAssistantRequest {

    @NotBlank
    private String message;

    private String conversationId;

    private Long accountId;

    /** Language code from client (e.g., "vi", "en"). */
    private String language;

    /** User ID from X-User-Id header (for scoping entries/accounts). */
    private Long userId;
}
