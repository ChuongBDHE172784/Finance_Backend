package com.example.finance_backend.repository;

import com.example.finance_backend.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** All active budgets for a user on a given date. */
    List<Budget> findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long userId, LocalDate date1, LocalDate date2);

    /** Budget for a specific category and user overlapping a date. */
    Optional<Budget> findFirstByUserIdAndCategoryIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long userId, Long categoryId, LocalDate date1, LocalDate date2);

    /** All budgets for a user. */
    List<Budget> findByUserId(Long userId);
}
