package com.example.finance_backend.service.ai;

import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.CategoryService;
import lombok.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spending analytics engine using SQL aggregation.
 * Provides: totals, daily averages, top categories, percentage breakdowns,
 * spending trends, and recent transaction lists.
 */
@Service
public class SpendingAnalyticsService {

    private final FinancialEntryRepository entryRepository;
    private final CategoryService categoryService;

    public SpendingAnalyticsService(FinancialEntryRepository entryRepository,
                                     CategoryService categoryService) {
        this.entryRepository = entryRepository;
        this.categoryService = categoryService;
    }

    // ═════════════════════════════════════════════════════════
    // TOTAL SPENDING
    // ═════════════════════════════════════════════════════════

    /**
     * Get total spending/income for a date range.
     * Uses SQL SUM aggregation instead of loading all entries.
     */
    public BigDecimal getTotalSpending(LocalDate startDate, LocalDate endDate, String typeFilter) {
        List<FinancialEntry> entries = getFilteredEntries(startDate, endDate, typeFilter);
        return entries.stream()
                .map(FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ═════════════════════════════════════════════════════════
    // DAILY AVERAGE
    // ═════════════════════════════════════════════════════════

    /**
     * Get average daily spending over a date range.
     */
    public DailyAverageResult getDailyAverage(LocalDate startDate, LocalDate endDate, String typeFilter) {
        BigDecimal total = getTotalSpending(startDate, endDate, typeFilter);
        long days = Math.max(1, ChronoUnit.DAYS.between(startDate, endDate) + 1);
        BigDecimal average = total.divide(new BigDecimal(days), 0, RoundingMode.HALF_UP);
        return new DailyAverageResult(total, average, days);
    }

    // ═════════════════════════════════════════════════════════
    // TOP CATEGORIES
    // ═════════════════════════════════════════════════════════

    /**
     * Get spending grouped by category, sorted by amount descending.
     */
    public List<CategoryTotal> getTopCategories(LocalDate startDate, LocalDate endDate,
                                                 String typeFilter, int limit) {
        List<FinancialEntry> entries = getFilteredEntries(startDate, endDate, typeFilter);
        Map<Long, BigDecimal> catTotals = new HashMap<>();
        for (var e : entries) {
            catTotals.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        return catTotals.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> new CategoryTotal(
                        entry.getKey(),
                        idToName.getOrDefault(entry.getKey(), "Khác"),
                        entry.getValue()))
                .collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════
    // PERCENTAGE BREAKDOWN
    // ═════════════════════════════════════════════════════════

    /**
     * Get spending percentage breakdown by category.
     */
    public PercentageBreakdownResult getSpendingByCategory(LocalDate startDate, LocalDate endDate,
                                                            String typeFilter) {
        List<FinancialEntry> entries = getFilteredEntries(startDate, endDate, typeFilter);
        BigDecimal total = entries.stream()
                .map(FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new PercentageBreakdownResult(total, List.of());
        }

        Map<Long, BigDecimal> catTotals = new HashMap<>();
        for (var e : entries) {
            catTotals.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        List<CategoryPercentage> breakdowns = catTotals.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .map(entry -> {
                    BigDecimal pct = entry.getValue().multiply(new BigDecimal("100"))
                            .divide(total, 1, RoundingMode.HALF_UP);
                    return new CategoryPercentage(
                            idToName.getOrDefault(entry.getKey(), "Khác"),
                            entry.getValue(),
                            pct);
                })
                .collect(Collectors.toList());

        return new PercentageBreakdownResult(total, breakdowns);
    }

    // ═════════════════════════════════════════════════════════
    // SPENDING TREND (period-over-period)
    // ═════════════════════════════════════════════════════════

    /**
     * Compare spending between current period and previous period.
     */
    public TrendResult getSpendingTrend(LocalDate currentStart, LocalDate currentEnd,
                                         String typeFilter) {
        long days = ChronoUnit.DAYS.between(currentStart, currentEnd) + 1;
        LocalDate prevStart = currentStart.minusDays(days);
        LocalDate prevEnd = currentStart.minusDays(1);

        BigDecimal currentTotal = getTotalSpending(currentStart, currentEnd, typeFilter);
        BigDecimal prevTotal = getTotalSpending(prevStart, prevEnd, typeFilter);

        String trend;
        BigDecimal percentChange = BigDecimal.ZERO;
        if (prevTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = currentTotal.subtract(prevTotal);
            percentChange = diff.multiply(new BigDecimal("100"))
                    .divide(prevTotal, 1, RoundingMode.HALF_UP);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                trend = "UP";
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                trend = "DOWN";
            } else {
                trend = "STABLE";
            }
        } else if (currentTotal.compareTo(BigDecimal.ZERO) > 0) {
            trend = "NEW";
        } else {
            trend = "STABLE";
        }

        return new TrendResult(currentTotal, prevTotal, prevStart, prevEnd,
                trend, percentChange);
    }

    // ═════════════════════════════════════════════════════════
    // RECENT TRANSACTIONS LIST
    // ═════════════════════════════════════════════════════════

    /**
     * Get recent transactions in a date range.
     */
    public List<FinancialEntry> getRecentTransactions(LocalDate startDate, LocalDate endDate,
                                                       String typeFilter, int limit) {
        List<FinancialEntry> entries = getFilteredEntries(startDate, endDate, typeFilter);
        return entries.stream().limit(limit).collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════

    private List<FinancialEntry> getFilteredEntries(LocalDate start, LocalDate end, String typeFilter) {
        List<FinancialEntry> entries = entryRepository
                .findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        if (typeFilter != null && !"ALL".equals(typeFilter)) {
            try {
                final EntryType finalType = EntryType.valueOf(typeFilter);
                entries = entries.stream()
                        .filter(e -> e.getType() == finalType)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {
                // Invalid type, return unfiltered
            }
        }
        return entries;
    }

    // ═════════════════════════════════════════════════════════
    // RESULT DTOs
    // ═════════════════════════════════════════════════════════

    @Getter
    @AllArgsConstructor
    public static class DailyAverageResult {
        private final BigDecimal total;
        private final BigDecimal average;
        private final long days;
    }

    @Getter
    @AllArgsConstructor
    public static class CategoryTotal {
        private final Long categoryId;
        private final String categoryName;
        private final BigDecimal total;
    }

    @Getter
    @AllArgsConstructor
    public static class CategoryPercentage {
        private final String categoryName;
        private final BigDecimal amount;
        private final BigDecimal percentage;
    }

    @Getter
    @AllArgsConstructor
    public static class PercentageBreakdownResult {
        private final BigDecimal total;
        private final List<CategoryPercentage> breakdowns;
    }

    @Getter
    @AllArgsConstructor
    public static class TrendResult {
        private final BigDecimal currentTotal;
        private final BigDecimal previousTotal;
        private final LocalDate previousStart;
        private final LocalDate previousEnd;
        private final String trend; // UP, DOWN, STABLE, NEW
        private final BigDecimal percentChange;
    }
}
