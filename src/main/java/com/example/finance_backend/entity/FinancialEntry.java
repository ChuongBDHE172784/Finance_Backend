package com.example.finance_backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "financial_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EntryType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 2000)
    private String note;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "to_account_id")
    private Long toAccountId;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /** Tags extracted from note, e.g. #an_uong #mua_sam - stored as comma-separated */
    @Column(length = 500)
    private String tags;

    /** Mentions extracted from note, e.g. @tiec_nuong - stored as comma-separated */
    @Column(length = 500)
    private String mentions;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EntrySource source;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
