package com.example.finance_backend.dto;

import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedEntry;
import lombok.*;

import java.util.List;

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
    private Boolean refreshRequired;
    private Boolean isDraft;
    private List<GeminiParsedEntry> entries;
}
