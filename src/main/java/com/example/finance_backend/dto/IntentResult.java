package com.example.finance_backend.dto;

import lombok.*;

/**
 * Result of intent classification. Contains the detected intent, confidence, and source.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentResult {

    public enum Intent {
        INSERT_TRANSACTION,
        QUERY_TRANSACTION,
        UPDATE_TRANSACTION,
        DELETE_TRANSACTION,
        FINANCIAL_ADVICE,
        GENERAL_CHAT,
        UNKNOWN
    }

    public enum Source {
        RULE, GEMINI
    }

    private Intent intent;
    @Builder.Default
    private double confidence = 0.0;
    @Builder.Default
    private Source source = Source.RULE;

    public boolean isHighConfidence() {
        return confidence >= 0.7;
    }

    /** Maps enum to the legacy string intents used in AiAssistantResponse */
    public String toLegacyIntent() {
        if (intent == null) return "UNKNOWN";
        return switch (intent) {
            case INSERT_TRANSACTION -> "INSERT";
            case QUERY_TRANSACTION -> "QUERY";
            case UPDATE_TRANSACTION -> "UPDATE";
            case DELETE_TRANSACTION -> "DELETE";
            case FINANCIAL_ADVICE -> "ADVICE";
            case GENERAL_CHAT -> "ADVICE";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
