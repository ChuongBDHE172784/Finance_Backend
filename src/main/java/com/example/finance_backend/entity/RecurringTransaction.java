package com.example.finance_backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "recurring_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntryType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 2000)
    private String note;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 50)
    private String frequency; // DAILY, WEEKLY, MONTHLY, YEARLY

    @Column(name = "next_run_date")
    private java.time.LocalDate nextRunDate;

    @Builder.Default
    private Boolean active = true;
}
