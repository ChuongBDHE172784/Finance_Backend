package com.example.finance_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEntryRequest {

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    private String note;

    @NotNull
    private Long categoryId;

    @NotNull
    private Long accountId;


    @NotNull
    private String type; // INCOME, EXPENSE, TRANSFER

    @NotNull(message = "Transaction date is required")
    private LocalDate transactionDate;


    private String imageUrl;
    private Double latitude;
    private Double longitude;
    private String source; // MANUAL, OCR, VOICE, CLIPBOARD
}
