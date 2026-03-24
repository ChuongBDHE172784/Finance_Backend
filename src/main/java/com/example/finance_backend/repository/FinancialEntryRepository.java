package com.example.finance_backend.repository;

import com.example.finance_backend.entity.FinancialEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface FinancialEntryRepository extends JpaRepository<FinancialEntry, Long> {

    List<FinancialEntry> findAllByOrderByTransactionDateDescCreatedAtDesc();

    List<FinancialEntry> findByUserIdOrderByTransactionDateDescCreatedAtDesc(Long userId);

    List<FinancialEntry> findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(
            Long userId, LocalDate start, LocalDate end);

    List<FinancialEntry> findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(
            LocalDate start, LocalDate end);

    long countByAccountId(Long accountId);

    // ── SQL Aggregation Queries ──

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM FinancialEntry e " +
           "WHERE e.userId = :userId AND e.type = :type " +
           "AND e.transactionDate BETWEEN :start AND :end")
    BigDecimal sumByUserIdAndTypeAndDateRange(Long userId,
            com.example.finance_backend.entity.EntryType type,
            LocalDate start, LocalDate end);

    @Query("SELECT e.categoryId, SUM(e.amount) FROM FinancialEntry e " +
           "WHERE e.userId = :userId AND e.type = :type " +
           "AND e.transactionDate BETWEEN :start AND :end " +
           "GROUP BY e.categoryId ORDER BY SUM(e.amount) DESC")
    List<Object[]> sumByCategoryForUser(Long userId,
            com.example.finance_backend.entity.EntryType type,
            LocalDate start, LocalDate end);

    @Query("SELECT COUNT(DISTINCT e.transactionDate) FROM FinancialEntry e " +
           "WHERE e.userId = :userId AND e.type = :type " +
           "AND e.transactionDate BETWEEN :start AND :end")
    long countDistinctDaysByUser(Long userId,
            com.example.finance_backend.entity.EntryType type,
            LocalDate start, LocalDate end);

    @Query("SELECT e.amount FROM FinancialEntry e " +
           "WHERE e.userId = :userId AND e.type = :type " +
           "AND e.transactionDate BETWEEN :start AND :end " +
           "ORDER BY e.transactionDate ASC")
    List<BigDecimal> findAmountsByUserAndTypeAndDateRange(Long userId,
            com.example.finance_backend.entity.EntryType type,
            LocalDate start, LocalDate end);
    @Modifying
    @Transactional
    @Query("UPDATE FinancialEntry e SET e.categoryId = :newCategoryId WHERE e.categoryId = :oldCategoryId")
    void updateCategoryId(Long oldCategoryId, Long newCategoryId);
}
