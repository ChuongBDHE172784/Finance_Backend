package com.example.finance_backend.service.ai;

import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.repository.BudgetRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class FinancialScoreEngineTest {

    @Mock private FinancialEntryRepository entryRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private CategoryService categoryService;

    private FinancialScoreEngine engine;
    private SpendingAnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new SpendingAnalyticsService(entryRepository, categoryService, budgetRepository);
        engine = new FinancialScoreEngine(entryRepository, budgetRepository, analyticsService);
    }

    @Test
    void testGradeMapping() {
        assertEquals("A", FinancialScoreEngine.toGrade(85));
        assertEquals("A", FinancialScoreEngine.toGrade(100));
        assertEquals("B", FinancialScoreEngine.toGrade(70));
        assertEquals("B", FinancialScoreEngine.toGrade(84));
        assertEquals("C", FinancialScoreEngine.toGrade(55));
        assertEquals("C", FinancialScoreEngine.toGrade(69));
        assertEquals("D", FinancialScoreEngine.toGrade(40));
        assertEquals("D", FinancialScoreEngine.toGrade(54));
        assertEquals("F", FinancialScoreEngine.toGrade(39));
        assertEquals("F", FinancialScoreEngine.toGrade(0));
    }

    @Test
    void testNoData_NeutralScore() {
        // All repos return zeros/empty
        when(entryRepository.sumByUserIdAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(entryRepository.findAmountsByUserAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(budgetRepository.findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());

        FinancialScoreEngine.FinancialScoreResult result = engine.computeScore(1L, 3, 2026);

        assertNotNull(result);
        assertTrue(result.getTotalScore() >= 0 && result.getTotalScore() <= 100);
        assertNotNull(result.getGrade());
    }

    @Test
    void testHighSavings_HighSavingsScore() {
        // Income = 10M, Expense = 5M → savings rate 50% → max score (30)
        when(entryRepository.sumByUserIdAndTypeAndDateRange(eq(1L), eq(EntryType.INCOME), any(), any()))
                .thenReturn(new BigDecimal("10000000"));
        when(entryRepository.sumByUserIdAndTypeAndDateRange(eq(1L), eq(EntryType.EXPENSE), any(), any()))
                .thenReturn(new BigDecimal("5000000"));
        when(entryRepository.findAmountsByUserAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(List.of(new BigDecimal("100000"), new BigDecimal("100000")));
        when(budgetRepository.findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());

        FinancialScoreEngine.FinancialScoreResult result = engine.computeScore(1L, 3, 2026);

        assertEquals(30, result.getSavingsRateScore()); // Max savings score
        assertTrue(result.getTotalScore() >= 30);
    }

    @Test
    void testOverspendingAllIncome_ZeroSavingsScore() {
        // Income = 10M, Expense = 12M → negative savings → 0 savings score
        when(entryRepository.sumByUserIdAndTypeAndDateRange(eq(1L), eq(EntryType.INCOME), any(), any()))
                .thenReturn(new BigDecimal("10000000"));
        when(entryRepository.sumByUserIdAndTypeAndDateRange(eq(1L), eq(EntryType.EXPENSE), any(), any()))
                .thenReturn(new BigDecimal("12000000"));
        when(entryRepository.findAmountsByUserAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(List.of(new BigDecimal("300000"), new BigDecimal("300000")));
        when(budgetRepository.findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());

        FinancialScoreEngine.FinancialScoreResult result = engine.computeScore(1L, 3, 2026);

        assertEquals(0, result.getSavingsRateScore());
    }

    @Test
    void testStableSpending_HighStabilityScore() {
        // Very consistent amounts → low CV → high stability score
        when(entryRepository.sumByUserIdAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(entryRepository.findAmountsByUserAndTypeAndDateRange(eq(1L), eq(EntryType.EXPENSE), any(), any()))
                .thenReturn(List.of(
                        new BigDecimal("50000"), new BigDecimal("50000"),
                        new BigDecimal("50000"), new BigDecimal("50000")));
        when(budgetRepository.findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());

        FinancialScoreEngine.FinancialScoreResult result = engine.computeScore(1L, 3, 2026);

        assertEquals(25, result.getStabilityScore()); // CV=0 → full score
    }

    @Test
    void testNoBudgets_NeutralBudgetScore() {
        when(entryRepository.sumByUserIdAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(entryRepository.findAmountsByUserAndTypeAndDateRange(anyLong(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(budgetRepository.findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());

        FinancialScoreEngine.FinancialScoreResult result = engine.computeScore(1L, 3, 2026);

        assertEquals(15, result.getBudgetAdherenceScore()); // Neutral when no budgets
    }
}
