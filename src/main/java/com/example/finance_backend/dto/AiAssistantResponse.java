package com.example.finance_backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAssistantResponse {
    private String reply;
    private String intent; // QUERY | INSERT | UNKNOWN
    private Integer createdCount;
    private String conversationId;
    private Boolean needsAccountSelection;
}
