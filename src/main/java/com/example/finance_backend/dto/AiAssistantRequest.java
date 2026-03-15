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
}
