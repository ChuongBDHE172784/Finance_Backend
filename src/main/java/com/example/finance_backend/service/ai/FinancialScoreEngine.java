package com.example.finance_backend.service.ai;

import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.repository.BudgetRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import lombok.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Tính toán Điểm Sức khỏe Tài chính từ 0–100 dựa trên 4 khía cạnh có trọng số:
 *   Tỷ lệ tiết kiệm        (30%)
 *   Sự ổn định chi tiêu    (25%)
 *   Tuân thủ ngân sách     (25%)
 *   Sự nhất quán thu nhập  (20%)
 *
 * Phân loại hạng: A (≥85), B (≥70), C (≥55), D (≥40), F (<40).
 */
@Service
public class FinancialScoreEngine {

    private static final int WEIGHT_SAVINGS    = 30;
    private static final int WEIGHT_STABILITY  = 25;
    private static final int WEIGHT_BUDGET     = 25;
    private static final int WEIGHT_INCOME     = 20;

    private final FinancialEntryRepository entryRepository;
    private final BudgetRepository budgetRepository;
    private final SpendingAnalyticsService analyticsService;

    public FinancialScoreEngine(FinancialEntryRepository entryRepository,
                                 BudgetRepository budgetRepository,
                                 SpendingAnalyticsService analyticsService) {
        this.entryRepository = entryRepository;
        this.budgetRepository = budgetRepository;
        this.analyticsService = analyticsService;
    }

    // ═════════════════════════════════════════════════════════
    // TÍNH TOÁN ĐIỂM CHÍNH
    // ═════════════════════════════════════════════════════════

    public FinancialScoreResult computeScore(Long userId, int month, int year) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        int savingsScore    = computeSavingsRateScore(userId, start, end);
        int stabilityScore  = computeSpendingStabilityScore(userId, start, end);
        int budgetScore     = computeBudgetAdherenceScore(userId, start, end);
        int incomeScore     = computeIncomeConsistencyScore(userId, start, end);

        int totalScore = savingsScore + stabilityScore + budgetScore + incomeScore;
        totalScore = Math.max(0, Math.min(100, totalScore));

        BigDecimal savingsRate = computeActualSavingsRate(userId, start, end);
        String grade = toGrade(totalScore);

        return new FinancialScoreResult(totalScore, savingsScore, stabilityScore,
                budgetScore, incomeScore, savingsRate, grade);
    }

    // ═════════════════════════════════════════════════════════
    // KHÍ CẠNH 1: TỶ LỆ TIẾT KIỆM (0–30 điểm)
    // 0% tiết kiệm → 0 điểm, ≥20% tiết kiệm → 30 điểm, tính tuyến tính ở giữa
    // ═════════════════════════════════════════════════════════

    int computeSavingsRateScore(Long userId, LocalDate start, LocalDate end) {
        BigDecimal income = analyticsService.getTotalSpendingForUser(userId, start, end, EntryType.INCOME);
        BigDecimal expense = analyticsService.getTotalSpendingForUser(userId, start, end, EntryType.EXPENSE);

        if (income.compareTo(BigDecimal.ZERO) <= 0) {
            // Không có thu nhập được ghi nhận — đưa ra số điểm trung tính
            return expense.compareTo(BigDecimal.ZERO) == 0 ? 15 : 0;
        }

        BigDecimal savings = income.subtract(expense);
        double savingsRatio = savings.doubleValue() / income.doubleValue();
        if (savingsRatio <= 0) return 0;
        if (savingsRatio >= 0.20) return WEIGHT_SAVINGS;

        // Nội suy tuyến tính: tỷ lệ/0.20 * 30
        return (int) Math.round((savingsRatio / 0.20) * WEIGHT_SAVINGS);
    }

    // ═════════════════════════════════════════════════════════
    // KHÍ CẠNH 2: SỰ ỔN ĐỊNH CHI TIÊU (0–25 điểm)
    // Hệ số biến thiên (std/mean) của chi tiêu hàng ngày
    // CV=0 → 25 điểm, CV≥1.0 → 0 điểm, tính tuyến tính ở giữa
    // ═════════════════════════════════════════════════════════

    int computeSpendingStabilityScore(Long userId, LocalDate start, LocalDate end) {
        List<BigDecimal> amounts = entryRepository
                .findAmountsByUserAndTypeAndDateRange(userId, EntryType.EXPENSE, start, end);

        if (amounts == null || amounts.size() < 2) {
            return amounts != null && amounts.size() == 1 ? 20 : 15; // trung tính
        }

        // Tính giá trị trung bình (mean)
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(new BigDecimal(amounts.size()), 10, RoundingMode.HALF_UP);
        if (mean.compareTo(BigDecimal.ZERO) <= 0) return WEIGHT_STABILITY;

        // Tính phương sai (variance)
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal a : amounts) {
            BigDecimal diff = a.subtract(mean);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(new BigDecimal(amounts.size()), 10, RoundingMode.HALF_UP);

        // Độ lệch chuẩn (Std deviation)
        double std = Math.sqrt(variance.doubleValue());
        double cv = std / mean.doubleValue();

        if (cv >= 1.0) return 0;
        if (cv <= 0) return WEIGHT_STABILITY;

        // Linear: (1 - cv) * 25
        return (int) Math.round((1.0 - cv) * WEIGHT_STABILITY);
    }

    // ═════════════════════════════════════════════════════════
    // KHÍ CẠNH 3: TUÂN THỦ NGÂN SÁCH (0–25 điểm)
    // % ngân sách CHƯA vượt quá → trọn vẹn 25 điểm
    // 0 ngân sách được thiết lập → trung tính 15 điểm
    // ═════════════════════════════════════════════════════════

    int computeBudgetAdherenceScore(Long userId, LocalDate start, LocalDate end) {
        List<Budget> budgets = budgetRepository
                .findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(userId, end, start);

        if (budgets.isEmpty()) return 15; // trung tính — không có ngân sách nào được cấu hình

        int withinBudget = 0;
        for (Budget b : budgets) {
            SpendingAnalyticsService.BudgetStatusResult status =
                    analyticsService.getBudgetStatus(userId, b.getCategoryId(),
                            b.getStartDate(), b.getEndDate());
            if (status != null && !status.isOverBudget()) {
                withinBudget++;
            }
        }

        double adherenceRatio = (double) withinBudget / budgets.size();
        return (int) Math.round(adherenceRatio * WEIGHT_BUDGET);
    }

    // ═════════════════════════════════════════════════════════
    // KHÍ CẠNH 4: SỰ NHẤT QUÁN THU NHẬP (0–20 điểm)
    // So sánh thu nhập tháng hiện tại với tháng trước
    // Cả hai đều có thu nhập → so sánh tính đều đặn; không có dữ liệu → trung tính
    // ═════════════════════════════════════════════════════════

    int computeIncomeConsistencyScore(Long userId, LocalDate start, LocalDate end) {
        BigDecimal currentIncome = analyticsService.getTotalSpendingForUser(userId, start, end, EntryType.INCOME);

        LocalDate prevStart = start.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());
        BigDecimal prevIncome = analyticsService.getTotalSpendingForUser(userId, prevStart, prevEnd, EntryType.INCOME);

        // Hoàn toàn không có dữ liệu thu nhập → trung tính
        if (currentIncome.compareTo(BigDecimal.ZERO) <= 0 && prevIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return 10;
        }
        // Chỉ có một tháng có thu nhập → một phần
        if (prevIncome.compareTo(BigDecimal.ZERO) <= 0) return 12;
        if (currentIncome.compareTo(BigDecimal.ZERO) <= 0) return 5;

        // Cả hai tháng đều có thu nhập — đo lường sự nhất quán
        BigDecimal diff = currentIncome.subtract(prevIncome).abs();
        double variationRatio = diff.doubleValue() / prevIncome.doubleValue();

        if (variationRatio <= 0.1) return WEIGHT_INCOME;  // Rất nhất quán
        if (variationRatio >= 0.5) return 5;               // Rất không nhất quán

        // Linear: (0.5 - ratio) / 0.4 * 20
        return (int) Math.round(((0.5 - variationRatio) / 0.4) * WEIGHT_INCOME);
    }

    // ═════════════════════════════════════════════════════════
    // PHƯƠNG THỨC HỖ TRỢ
    // ═════════════════════════════════════════════════════════

    private BigDecimal computeActualSavingsRate(Long userId, LocalDate start, LocalDate end) {
        BigDecimal income = analyticsService.getTotalSpendingForUser(userId, start, end, EntryType.INCOME);
        BigDecimal expense = analyticsService.getTotalSpendingForUser(userId, start, end, EntryType.EXPENSE);
        if (income.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return income.subtract(expense).multiply(new BigDecimal("100"))
                .divide(income, 1, RoundingMode.HALF_UP);
    }

    static String toGrade(int score) {
        if (score >= 85) return "A";
        if (score >= 70) return "B";
        if (score >= 55) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    // ═════════════════════════════════════════════════════════
    // DTO KẾT QUẢ
    // ═════════════════════════════════════════════════════════

    @Getter
    @AllArgsConstructor
    public static class FinancialScoreResult {
        private final int totalScore;             // 0-100
        private final int savingsRateScore;       // 0-30
        private final int stabilityScore;         // 0-25
        private final int budgetAdherenceScore;   // 0-25
        private final int incomeConsistencyScore; // 0-20
        private final BigDecimal savingsRate;     // tỷ lệ % thực tế để hiển thị
        private final String grade;               // A/B/C/D/F
    }
}
