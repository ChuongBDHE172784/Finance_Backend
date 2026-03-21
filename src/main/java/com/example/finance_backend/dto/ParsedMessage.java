package com.example.finance_backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of text pre-processing. Contains normalized text, extracted monetary values,
 * detected dates, and sub-messages for multi-transaction input.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedMessage {
    /** Original user message */
    private String originalText;
    /** Normalized text (Vietnamese diacritics removed, lowercased) */
    private String normalizedText;
    /** Detected language: "vi" or "en" */
    private String language;
    /** All monetary amounts extracted from the message */
    private List<BigDecimal> extractedAmounts;
    /** Date range detected in the message */
    private LocalDate startDate;
    private LocalDate endDate;
    /** Sub-messages for multi-transaction input (e.g., "phở 45k, cà phê 30k" → 2 items) */
    private List<String> subMessages;

    public boolean hasAmounts() {
        return extractedAmounts != null && !extractedAmounts.isEmpty();
    }

    public boolean isMultiTransaction() {
        return subMessages != null && subMessages.size() > 1;
    }

    public BigDecimal getFirstAmount() {
        return hasAmounts() ? extractedAmounts.get(0) : null;
    }
}
