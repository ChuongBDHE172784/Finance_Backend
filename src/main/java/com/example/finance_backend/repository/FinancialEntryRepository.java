package com.example.finance_backend.repository;

import com.example.finance_backend.entity.FinancialEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface FinancialEntryRepository extends JpaRepository<FinancialEntry, Long> {

    List<FinancialEntry> findAllByOrderByTransactionDateDescCreatedAtDesc();

    List<FinancialEntry> findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(
            LocalDate start, LocalDate end);

    @Query("SELECT e FROM FinancialEntry e WHERE e.tags IS NOT NULL AND e.tags != '' AND LOWER(e.tags) LIKE LOWER(CONCAT('%', :tag, '%')) ORDER BY e.transactionDate DESC")
    List<FinancialEntry> findByTagContaining(String tag);

    @Query("SELECT e FROM FinancialEntry e WHERE e.mentions IS NOT NULL AND e.mentions != '' AND LOWER(e.mentions) LIKE LOWER(CONCAT('%', :mention, '%')) ORDER BY e.transactionDate DESC")
    List<FinancialEntry> findByMentionContaining(String mention);
}
