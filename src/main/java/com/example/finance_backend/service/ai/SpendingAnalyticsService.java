package com.example.finance_backend.service.ai;

import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.entity.Category;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.BudgetRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.CategoryService;
import lombok.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Công cụ phân tích chi tiêu.
 * Cung cấp: tổng số, trung bình hàng ngày, các danh mục hàng đầu, phân bổ phần trăm,
 * xu hướng chi tiêu, theo dõi ngân sách, cảnh báo chi tiêu quá mức, tóm tắt hàng tháng,
 * sức khỏe tài chính, quy luật hàng tuần, và các gợi ý thông minh.
 */
@Service
public class SpendingAnalyticsService {

    private final FinancialEntryRepository entryRepository;
    private final CategoryService categoryService;
    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;

    public SpendingAnalyticsService(FinancialEntryRepository entryRepository,
                                     CategoryService categoryService,
                                     BudgetRepository budgetRepository,
                                     CategoryRepository categoryRepository) {
        this.entryRepository = entryRepository;
        this.categoryService = categoryService;
        this.budgetRepository = budgetRepository;
        this.categoryRepository = categoryRepository;
    }

    // ═════════════════════════════════════════════════════════
    // TỔNG CHI TIÊU (phạm vi userId)
    // ═════════════════════════════════════════════════════════

    public BigDecimal getTotalSpending(LocalDate startDate, LocalDate endDate, String typeFilter) {
        List<FinancialEntry> entries = getFilteredEntries(startDate, endDate, typeFilter);
        return entries.stream()
                .map(FinancialEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalSpendingForUser(Long userId, LocalDate start, LocalDate end, EntryType type) {
        if (userId == null) return getTotalSpending(start, end, type.name());
        return entryRepository.sumByUserIdAndTypeAndDateRange(userId, type, start, end);
    }

    // ═════════════════════════════════════════════════════════
    // TRUNG BÌNH HÀNG NGÀY
    // ═════════════════════════════════════════════════════════

    public DailyAverageResult getDailyAverage(LocalDate startDate, LocalDate endDate, String typeFilter) {
        BigDecimal total = getTotalSpending(startDate, endDate, typeFilter);
        long days = Math.max(1, ChronoUnit.DAYS.between(startDate, endDate) + 1);
        BigDecimal average = total.divide(new BigDecimal(days), 0, RoundingMode.HALF_UP);
        return new DailyAverageResult(total, average, days);
    }

    // ═════════════════════════════════════════════════════════
    // DANH MỤC HÀNG ĐẦU
    // ═════════════════════════════════════════════════════════

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
    // PHÂN BỔ PHẦN TRĂM
    // ═════════════════════════════════════════════════════════

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
    // XU HƯỚNG CHI TIÊU (so sánh giữa các kỳ)
    // ═════════════════════════════════════════════════════════

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
    // DANH SÁCH GIAO DỊCH GẦN ĐÂY
    // ═════════════════════════════════════════════════════════

    public List<FinancialEntry> getRecentTransactions(LocalDate startDate, LocalDate endDate,
                                                       String typeFilter, int limit) {
        List<FinancialEntry> entries = getFilteredEntries(startDate, endDate, typeFilter);
        return entries.stream().limit(limit).collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════
    // THEO DÕI NGÂN SÁCH
    // ═════════════════════════════════════════════════════════

    /**
     * Lấy trạng thái ngân sách cho một danh mục cụ thể hoặc tất cả ngân sách.
     */
    public BudgetStatusResult getBudgetStatus(Long userId, Long categoryId,
                                               LocalDate start, LocalDate end) {
        Optional<Budget> budgetOpt = budgetRepository
                .findFirstByUserIdAndCategoryIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        userId, categoryId, end, start);
        if (budgetOpt.isEmpty()) return null;

        Budget budget = budgetOpt.get();
        String categoryName = categoryService.getIdToNameMap().getOrDefault(categoryId, "Khác");

        // Xác định loại danh mục để phân nhánh logic
        EntryType categoryType = categoryRepository.findById(categoryId)
                .map(Category::getType)
                .orElse(EntryType.EXPENSE);

        List<FinancialEntry> entries = entryRepository
                .findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(userId, start, end);

        if (categoryType == EntryType.INCOME) {
            // MỤC TIÊU THU NHẬP: tính toán số tiền đã thu so với mục tiêu
            BigDecimal earned = entries.stream()
                    .filter(e -> e.getType() == EntryType.INCOME && Objects.equals(e.getCategoryId(), categoryId))
                    .map(FinancialEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal percentUsed = budget.getAmount().compareTo(BigDecimal.ZERO) > 0
                    ? earned.multiply(new BigDecimal("100")).divide(budget.getAmount(), 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            boolean achieved = earned.compareTo(budget.getAmount()) >= 0;
            BigDecimal overAmount = achieved ? earned.subtract(budget.getAmount()) : BigDecimal.ZERO;

            return new BudgetStatusResult(categoryName, budget.getAmount(), earned,
                    percentUsed, achieved, overAmount, "INCOME_TARGET");
        } else {
            // NGÂN SÁCH CHI TIÊU: tính toán số tiền đã chi so với hạn mức (logic hiện tại)
            BigDecimal categorySpent = entries.stream()
                    .filter(e -> e.getType() == EntryType.EXPENSE && Objects.equals(e.getCategoryId(), categoryId))
                    .map(FinancialEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal percentUsed = budget.getAmount().compareTo(BigDecimal.ZERO) > 0
                    ? categorySpent.multiply(new BigDecimal("100")).divide(budget.getAmount(), 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            boolean isOver = categorySpent.compareTo(budget.getAmount()) > 0;
            BigDecimal overAmount = isOver ? categorySpent.subtract(budget.getAmount()) : BigDecimal.ZERO;

            return new BudgetStatusResult(categoryName, budget.getAmount(), categorySpent,
                    percentUsed, isOver, overAmount, "EXPENSE_BUDGET");
        }
    }

    /**
     * Lấy tất cả các trạng thái ngân sách đang hoạt động của người dùng.
     */
    public List<BudgetStatusResult> getAllBudgetStatuses(Long userId, LocalDate date) {
        List<Budget> budgets = budgetRepository
                .findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(userId, date, date);
        if (budgets.isEmpty()) return List.of();

        List<BudgetStatusResult> results = new ArrayList<>();
        for (Budget b : budgets) {
            BudgetStatusResult status = getBudgetStatus(userId, b.getCategoryId(),
                    b.getStartDate(), b.getEndDate());
            if (status != null) results.add(status);
        }
        return results;
    }

    // ═════════════════════════════════════════════════════════
    // CẢNH BÁO CHI TIÊU QUÁ MỨC
    // ═════════════════════════════════════════════════════════

    /**
     * Phát hiện các danh mục có chi tiêu bất thường so với mức trung bình lịch sử.
     */
    public List<OverspendingAlert> getOverspendingAlerts(Long userId, LocalDate currentStart, LocalDate currentEnd) {
        long days = ChronoUnit.DAYS.between(currentStart, currentEnd) + 1;
        LocalDate prevStart = currentStart.minusDays(days);
        LocalDate prevEnd = currentStart.minusDays(1);

        List<FinancialEntry> currentEntries = userId != null
                ? entryRepository.findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(userId, currentStart, currentEnd)
                : entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(currentStart, currentEnd);
        List<FinancialEntry> prevEntries = userId != null
                ? entryRepository.findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(userId, prevStart, prevEnd)
                : entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(prevStart, prevEnd);

        Map<Long, BigDecimal> currentByCat = groupByCategory(currentEntries.stream()
                .filter(e -> e.getType() == EntryType.EXPENSE).collect(Collectors.toList()));
        Map<Long, BigDecimal> prevByCat = groupByCategory(prevEntries.stream()
                .filter(e -> e.getType() == EntryType.EXPENSE).collect(Collectors.toList()));

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        List<OverspendingAlert> alerts = new ArrayList<>();

        for (var entry : currentByCat.entrySet()) {
            BigDecimal prevAmount = prevByCat.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (prevAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal diff = entry.getValue().subtract(prevAmount);
                BigDecimal pctChange = diff.multiply(new BigDecimal("100"))
                        .divide(prevAmount, 1, RoundingMode.HALF_UP);
                if (pctChange.compareTo(new BigDecimal("30")) > 0) { // tăng >30%
                    alerts.add(new OverspendingAlert(
                            idToName.getOrDefault(entry.getKey(), "Khác"),
                            entry.getValue(), prevAmount, pctChange));
                }
            }
        }
        alerts.sort((a, b) -> b.getPercentIncrease().compareTo(a.getPercentIncrease()));
        return alerts;
    }

    // ═════════════════════════════════════════════════════════
    // TÓM TẮT HÀNG THÁNG
    // ═════════════════════════════════════════════════════════

    public MonthlySummaryResult getMonthlySummary(Long userId, int month, int year) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate prevStart = start.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());

        BigDecimal totalExpense = getTotalSpendingForUser(userId, start, end, EntryType.EXPENSE);
        BigDecimal totalIncome = getTotalSpendingForUser(userId, start, end, EntryType.INCOME);
        BigDecimal prevExpense = getTotalSpendingForUser(userId, prevStart, prevEnd, EntryType.EXPENSE);

        // Phân bổ theo danh mục
        List<FinancialEntry> entries = userId != null
                ? entryRepository.findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(userId, start, end)
                : entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);
        Map<Long, BigDecimal> catTotals = groupByCategory(entries.stream()
                .filter(e -> e.getType() == EntryType.EXPENSE).collect(Collectors.toList()));

        Map<Long, String> idToName = categoryService.getIdToNameMap();
        List<CategoryPercentage> breakdowns = new ArrayList<>();
        if (totalExpense.compareTo(BigDecimal.ZERO) > 0) {
            breakdowns = catTotals.entrySet().stream()
                    .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                    .map(entry -> {
                        BigDecimal pct = entry.getValue().multiply(new BigDecimal("100"))
                                .divide(totalExpense, 1, RoundingMode.HALF_UP);
                        return new CategoryPercentage(
                                idToName.getOrDefault(entry.getKey(), "Khác"),
                                entry.getValue(), pct);
                    })
                    .collect(Collectors.toList());
        }

        // Xu hướng so với tháng trước
        BigDecimal trendPct = BigDecimal.ZERO;
        String trendDir = "STABLE";
        if (prevExpense.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = totalExpense.subtract(prevExpense);
            trendPct = diff.multiply(new BigDecimal("100"))
                    .divide(prevExpense, 1, RoundingMode.HALF_UP);
            trendDir = diff.compareTo(BigDecimal.ZERO) > 0 ? "UP"
                    : diff.compareTo(BigDecimal.ZERO) < 0 ? "DOWN" : "STABLE";
        }

        return new MonthlySummaryResult(totalExpense, totalIncome, breakdowns, trendDir, trendPct);
    }

    // ═════════════════════════════════════════════════════════
    // SỨC KHỎE TÀI CHÍNH
    // ═════════════════════════════════════════════════════════

    public FinancialHealthResult getFinancialHealth(Long userId, int month, int year) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        BigDecimal totalIncome = getTotalSpendingForUser(userId, start, end, EntryType.INCOME);
        BigDecimal totalExpense = getTotalSpendingForUser(userId, start, end, EntryType.EXPENSE);

        BigDecimal savingsRate = BigDecimal.ZERO;
        BigDecimal ratio = BigDecimal.ZERO;
        if (totalIncome.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal savings = totalIncome.subtract(totalExpense);
            savingsRate = savings.multiply(new BigDecimal("100"))
                    .divide(totalIncome, 1, RoundingMode.HALF_UP);
            ratio = totalExpense.multiply(new BigDecimal("100"))
                    .divide(totalIncome, 1, RoundingMode.HALF_UP);
        }

        return new FinancialHealthResult(totalIncome, totalExpense, savingsRate, ratio);
    }

    // ═════════════════════════════════════════════════════════
    // QUY LUẬT HÀNG TUẦN
    // ═════════════════════════════════════════════════════════

    public WeeklyPatternResult getWeeklyPatterns(Long userId, LocalDate start, LocalDate end) {
        List<FinancialEntry> entries = userId != null
                ? entryRepository.findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(userId, start, end)
                : entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end);

        List<FinancialEntry> expenses = entries.stream()
                .filter(e -> e.getType() == EntryType.EXPENSE)
                .collect(Collectors.toList());

        BigDecimal weekdayTotal = BigDecimal.ZERO;
        BigDecimal weekendTotal = BigDecimal.ZERO;
        int weekdayCount = 0, weekendCount = 0;
        Map<DayOfWeek, BigDecimal> dayTotals = new EnumMap<>(DayOfWeek.class);

        for (FinancialEntry e : expenses) {
            DayOfWeek dow = e.getTransactionDate().getDayOfWeek();
            dayTotals.merge(dow, e.getAmount(), BigDecimal::add);
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                weekendTotal = weekendTotal.add(e.getAmount());
                weekendCount++;
            } else {
                weekdayTotal = weekdayTotal.add(e.getAmount());
                weekdayCount++;
            }
        }

        BigDecimal weekdayAvg = weekdayCount > 0
                ? weekdayTotal.divide(new BigDecimal(weekdayCount), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal weekendAvg = weekendCount > 0
                ? weekendTotal.divide(new BigDecimal(weekendCount), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        DayOfWeek peakDay = dayTotals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(DayOfWeek.MONDAY);

        return new WeeklyPatternResult(weekdayAvg, weekendAvg, peakDay,
                weekendAvg.compareTo(weekdayAvg) > 0);
    }

    // ═════════════════════════════════════════════════════════
    // GỢI Ý THÔNG MINH
    // ═════════════════════════════════════════════════════════

    public List<SmartSuggestionResult> getSmartSuggestions(Long userId) {
        LocalDate now = LocalDate.now();
        LocalDate start = now.withDayOfMonth(1);
        LocalDate end = now;

        List<CategoryTotal> topCats = getTopCategoriesForUser(userId, start, end, 3);
        List<SmartSuggestionResult> suggestions = new ArrayList<>();

        for (CategoryTotal cat : topCats) {
            BigDecimal potentialSavings = cat.getTotal()
                    .multiply(new BigDecimal("0.15"))
                    .setScale(0, RoundingMode.HALF_UP);
            if (potentialSavings.compareTo(new BigDecimal("10000")) > 0) {
                suggestions.add(new SmartSuggestionResult(cat.getCategoryName(), potentialSavings));
            }
        }
        return suggestions;
    }

    private List<CategoryTotal> getTopCategoriesForUser(Long userId, LocalDate start, LocalDate end, int limit) {
        if (userId != null) {
            List<Object[]> rows = entryRepository.sumByCategoryForUser(userId, EntryType.EXPENSE, start, end);
            Map<Long, String> idToName = categoryService.getIdToNameMap();
            return rows.stream()
                    .limit(limit)
                    .map(row -> new CategoryTotal(
                            (Long) row[0],
                            idToName.getOrDefault((Long) row[0], "Khác"),
                            (BigDecimal) row[1]))
                    .collect(Collectors.toList());
        }
        return getTopCategories(start, end, "EXPENSE", limit);
    }

    // ═════════════════════════════════════════════════════════
    // PHƯƠNG THỨC HỖ TRỢ RIÊNG
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
                // Loại không hợp lệ, trả về kết quả không lọc
            }
        }
        return entries;
    }

    private Map<Long, BigDecimal> groupByCategory(List<FinancialEntry> entries) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (var e : entries) {
            map.merge(e.getCategoryId(), e.getAmount(), BigDecimal::add);
        }
        return map;
    }

    // ═════════════════════════════════════════════════════════
    // DTO KẾT QUẢ
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
        private final String trend; // TĂNG, GIẢM, ỔN ĐỊNH, MỚI
        private final BigDecimal percentChange;
    }

    @Getter
    @AllArgsConstructor
    public static class BudgetStatusResult {
        private final String categoryName;
        private final BigDecimal budgetAmount;
        private final BigDecimal spentAmount;
        private final BigDecimal percentUsed;
        private final boolean overBudget;
        private final BigDecimal overAmount;
        /** "EXPENSE_BUDGET" hoặc "INCOME_TARGET" */
        private final String planType;
    }

    @Getter
    @AllArgsConstructor
    public static class OverspendingAlert {
        private final String categoryName;
        private final BigDecimal currentAmount;
        private final BigDecimal previousAmount;
        private final BigDecimal percentIncrease;
    }

    @Getter
    @AllArgsConstructor
    public static class MonthlySummaryResult {
        private final BigDecimal totalExpense;
        private final BigDecimal totalIncome;
        private final List<CategoryPercentage> categoryBreakdowns;
        private final String trendDirection; // TĂNG, GIẢM, ỔN ĐỊNH
        private final BigDecimal trendPercent;
    }

    @Getter
    @AllArgsConstructor
    public static class FinancialHealthResult {
        private final BigDecimal totalIncome;
        private final BigDecimal totalExpense;
        private final BigDecimal savingsRate;
        private final BigDecimal expenseToIncomeRatio;
    }

    @Getter
    @AllArgsConstructor
    public static class WeeklyPatternResult {
        private final BigDecimal weekdayAverage;
        private final BigDecimal weekendAverage;
        private final DayOfWeek peakDay;
        private final boolean spendsMoreOnWeekends;
    }

    @Getter
    @AllArgsConstructor
    public static class SmartSuggestionResult {
        private final String categoryName;
        private final BigDecimal potentialSavings;
    }
}
