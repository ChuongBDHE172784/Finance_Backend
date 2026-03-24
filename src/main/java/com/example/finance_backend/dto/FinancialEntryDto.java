package com.example.finance_backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private List<String> tags;
    private List<String> mentions;
    private String imageUrl;
    private Double latitude;
    private Double longitude;
    private String type;
    private Long accountId;
    private String accountName;
    private String accountIconName;
    private String accountColorHex;
    private Long toAccountId;
    private String toAccountName;
    private String toAccountIconName;
    private String toAccountColorHex;
    private String source;
    private LocalDateTime createdAt;

    public static FinancialEntryDto fromEntity(
            com.example.finance_backend.entity.FinancialEntry e,
            com.example.finance_backend.entity.Category category,
            com.example.finance_backend.entity.Account account,
            com.example.finance_backend.entity.Account toAccount) {
        return FinancialEntryDto.builder()
                .id(e.getId())
                .amount(e.getAmount())
                .note(e.getNote())
                .categoryId(e.getCategoryId())
                .categoryName(category != null ? category.getName() : "")
                .categoryIconName(category != null ? category.getIconName() : null)
                .categoryColorHex(category != null ? category.getColorHex() : null)
                .transactionDate(e.getTransactionDate())
                .tags(splitToList(e.getTags()))
                .mentions(splitToList(e.getMentions()))
                .imageUrl(e.getImageUrl())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .type(e.getType() != null ? e.getType().name() : "EXPENSE")
                .accountId(e.getAccountId())
                .accountName(account != null ? account.getName() : "")
                .accountIconName(account != null ? account.getIconName() : null)
                .accountColorHex(account != null ? account.getColorHex() : null)
                .toAccountId(e.getToAccountId())
                .toAccountName(toAccount != null ? toAccount.getName() : "")
                .toAccountIconName(toAccount != null ? toAccount.getIconName() : null)
                .toAccountColorHex(toAccount != null ? toAccount.getColorHex() : null)
                .source(e.getSource() != null ? e.getSource().name() : null)
                .createdAt(e.getCreatedAt())
                .build();
    }

    private static List<String> splitToList(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Stream.of(s.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }
}
