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
        BUDGET_QUERY,
        MONTHLY_SUMMARY,
        FINANCIAL_SCORE,
        GENERAL_CHAT,
        CREATE_BUDGET,
        CREATE_INCOME_GOAL,
        VIEW_FINANCIAL_PLAN,
        CREATE_SCHEDULE,
        UPDATE_SCHEDULE,
        DELETE_SCHEDULE,
        DISABLE_SCHEDULE,
        ENABLE_SCHEDULE,
        LIST_SCHEDULES,
        LIST_UPCOMING_TRANSACTIONS,
        EXPLAIN_TRANSACTION_SOURCE,
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
            case BUDGET_QUERY -> "QUERY";
            case MONTHLY_SUMMARY -> "QUERY";
            case FINANCIAL_SCORE -> "QUERY";
            case GENERAL_CHAT -> "ADVICE";
            case CREATE_BUDGET -> "QUERY";
            case CREATE_INCOME_GOAL -> "QUERY";
            case VIEW_FINANCIAL_PLAN -> "QUERY";
            case CREATE_SCHEDULE, UPDATE_SCHEDULE, DELETE_SCHEDULE, DISABLE_SCHEDULE, ENABLE_SCHEDULE -> "SCHEDULE";
            case LIST_SCHEDULES, LIST_UPCOMING_TRANSACTIONS -> "QUERY";
            case EXPLAIN_TRANSACTION_SOURCE -> "QUERY";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
