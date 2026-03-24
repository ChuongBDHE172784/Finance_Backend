package com.example.finance_backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialEntryDto {
    private Long id;
    private BigDecimal amount;
    private String note;
    private Long categoryId;
    private String categoryName;
    private String categoryIconName;
    private String categoryColorHex;
    private LocalDate transactionDate;
    private String imageUrl;
    private Double latitude;
    private Double longitude;
    private String locationName;
    private String type;
    private Long accountId;
    private String accountName;
    private String accountIconName;
    private String accountColorHex;
    private String source;
    private LocalDateTime createdAt;

    public static FinancialEntryDto fromEntity(
            com.example.finance_backend.entity.FinancialEntry e,
            com.example.finance_backend.entity.Category category,
            com.example.finance_backend.entity.Account account) {
        return FinancialEntryDto.builder()
                .id(e.getId())
                .amount(e.getAmount())
                .note(e.getNote())
                .categoryId(e.getCategoryId())
                .categoryName(category != null ? category.getName() : "")
                .categoryIconName(category != null ? category.getIconName() : null)
                .categoryColorHex(category != null ? category.getColorHex() : null)
                .transactionDate(e.getTransactionDate())
                .imageUrl(e.getImageUrl())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .locationName(e.getLocationName())
                .type(e.getType() != null ? e.getType().name() : "EXPENSE")
                .accountId(e.getAccountId())
                .accountName(account != null ? account.getName() : "")
                .accountIconName(account != null ? account.getIconName() : null)
                .accountColorHex(account != null ? account.getColorHex() : null)
                .source(e.getSource() != null ? e.getSource().name() : null)
                .createdAt(e.getCreatedAt())
                .build();
    }


}
