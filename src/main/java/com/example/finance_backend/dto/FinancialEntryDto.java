package com.example.finance_backend.dto;

import com.example.finance_backend.entity.FinancialEntry;
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
    private LocalDate transactionDate;
    private List<String> tags;
    private List<String> mentions;
    private String imageUrl;
    private Double latitude;
    private Double longitude;
    private String type;
    private Long accountId;
    private String accountName;
    private Long toAccountId;
    private String toAccountName;
    private String source;
    private LocalDateTime createdAt;

    public static FinancialEntryDto fromEntity(FinancialEntry e, String categoryName, String accountName, String toAccountName) {
        return FinancialEntryDto.builder()
                .id(e.getId())
                .amount(e.getAmount())
                .note(e.getNote())
                .categoryId(e.getCategoryId())
                .categoryName(categoryName)
                .transactionDate(e.getTransactionDate())
                .tags(splitToList(e.getTags()))
                .mentions(splitToList(e.getMentions()))
                .imageUrl(e.getImageUrl())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .type(e.getType() != null ? e.getType().name() : "EXPENSE")
                .accountId(e.getAccountId())
                .accountName(accountName)
                .toAccountId(e.getToAccountId())
                .toAccountName(toAccountName)
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
