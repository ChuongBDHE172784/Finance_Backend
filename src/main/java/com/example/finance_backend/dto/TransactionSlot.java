package com.example.finance_backend.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * Represents a parsed transaction slot — an extracted (possibly incomplete) transaction
 * from natural language input. Used in the AI pipeline for slot-filling.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionSlot {
    private BigDecimal amount;
    private String categoryName;
    private String note;
    /** EXPENSE or INCOME */
    private String type;
    /** ISO date string YYYY-MM-DD */
    private String date;
    /** NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM */
    private String repeatType;
    /** JSON string representing the config, e.g. {"day_of_month": 5} */
    private String repeatConfig;
    /** 0.0 – 1.0 confidence score from extraction */
    @Builder.Default
    private double confidence = 1.0;

    /** Returns true if the slot is missing the critical amount field. */
    public boolean isMissingCriticalInfo() {
        return amount == null || amount.compareTo(BigDecimal.ZERO) <= 0;
    }

    /** Returns true if this looks like a complete transaction ready to save. */
    public boolean isComplete() {
        return !isMissingCriticalInfo() && note != null && !note.isBlank();
    }
}
