package com.example.finance_backend.service.ai;

import com.example.finance_backend.service.ai.FinancialScoreEngine.FinancialScoreResult;
import com.example.finance_backend.service.ai.SpendingAnalyticsService.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates natural language responses in Vietnamese and English.
 * Handles currency formatting, clarification templates, confirmation messages,
 * financial summary formatting, budget status, score reports, and smart suggestions.
 */
@Component
public class ResponseGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Locale VI_LOCALE = new Locale("vi", "VN");

    // ═════════════════════════════════════════════════════════
    // TRANSLATION HELPER
    // ═════════════════════════════════════════════════════════

    public String t(String language, String vi, String en) {
        return "en".equals(language) ? en : vi;
    }

    public String t(String language, String vi, String en, String ja, String ko, String zh) {
        if ("en".equals(language)) return en;
        if ("ja".equals(language)) return ja;
        if ("ko".equals(language)) return ko;
        if ("zh".equals(language)) return zh;
        return vi;
    }

    public boolean isEnglish(String language) {
        return "en".equals(language);
    }

    // ═════════════════════════════════════════════════════════
    // CURRENCY FORMATTING
    // ═════════════════════════════════════════════════════════

    public String formatVnd(BigDecimal value, String language) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
        Locale locale = switch (language) {
            case "en" -> Locale.US;
            case "ja" -> Locale.JAPAN;
            case "ko" -> Locale.KOREA;
            case "zh" -> Locale.CHINA;
            default -> VI_LOCALE;
        };
        String suffix = switch (language) {
            case "en" -> " VND";
            case "ja" -> " 円";
            case "ko" -> " 원";
            case "zh" -> " 元";
            default -> " đ";
        };
        return NumberFormat.getInstance(locale).format(normalized) + suffix;
    }

    // ═════════════════════════════════════════════════════════
    // INSERT RESPONSES
    // ═════════════════════════════════════════════════════════

    public String insertSuccess(int count, java.util.List<String> details, String language) {
        StringBuilder reply = new StringBuilder();
        reply.append(t(language, "Đã lưu ", "Saved "))
                .append(count)
                .append(t(language, " giao dịch.", " transaction(s)."));
        if (details != null && !details.isEmpty()) {
            reply.append("\n").append(String.join("\n", details));
        }
        return reply.toString();
    }

    public String insertEmpty(String language) {
        return t(language,
                "Mình chưa thấy khoản chi/thu nào rõ ràng. Bạn thử ghi: \"Hôm nay ăn phở 45k\" nhé.",
                "I couldn't detect a clear transaction. Try: \"I had pho for 45k today\".");
    }

    public String insertFailed(String error, String language) {
        return error != null && !error.isBlank()
                ? t(language, "Không thể lưu giao dịch: ", "Couldn't save transaction: ") + error
                : t(language,
                "Mình chưa thể tạo giao dịch. Hãy thử nhập rõ số tiền và nội dung nhé.",
                "I couldn't create the transaction. Please include a clear amount and description.");
    }

    public String draftMessage(java.util.List<GeminiClientWrapper.GeminiParsedEntry> entries, String language) {
        if (entries == null || entries.isEmpty()) return insertEmpty(language);
        
        StringBuilder sb = new StringBuilder();
        sb.append(t(language, 
            "Mình đã tìm thấy thông tin giao dịch sau từ nội dung bạn gửi.\n\n", 
            "I found the following transaction information from what you sent.\n\n"));
        
        for (var entry : entries) {
            String catName = entry.categoryName != null ? entry.categoryName : "Khác";
            String note = entry.note != null ? entry.note : "";
            String type = "INCOME".equalsIgnoreCase(entry.type) ? "+" : "-";
            sb.append(String.format("%s %s • %s", type, formatVnd(entry.amount, language), catName));
            if (!note.isBlank()) sb.append(" (").append(trimNote(note)).append(")");
            sb.append("\n");
        }
        
        sb.append(t(language, 
            "\nBạn hãy kiểm tra lại nhé. Bạn có thể yêu cầu mình thay đổi bất kỳ thông tin nào trước khi lưu.\n\nBạn có muốn lưu giao dịch này không?", 
            "\nPlease review it. You can ask me to modify anything before saving.\n\nDo you want to save this transaction?"));
        
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════
    // QUERY RESPONSES
    // ═════════════════════════════════════════════════════════

    public String totalReply(LocalDate start, LocalDate end, BigDecimal total, String typeLabel, String language) {
        return isEnglish(language)
                ? String.format("%s from %s to %s is %s.", typeLabel, start.format(DATE_FMT), end.format(DATE_FMT), formatVnd(total, language))
                : String.format("%s từ %s đến %s là %s.", typeLabel, start.format(DATE_FMT), end.format(DATE_FMT), formatVnd(total, language));
    }

    public String averageReply(LocalDate start, LocalDate end, BigDecimal average, long days, String verb, String language) {
        return isEnglish(language)
                ? String.format("From %s to %s (%d days), your average daily %s is %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), days, verb, formatVnd(average, language))
                : String.format("Trong khoảng từ %s đến %s (%d ngày), trung bình mỗi ngày bạn %s %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), days, verb, formatVnd(average, language));
    }

    public String topCategoryReply(LocalDate start, LocalDate end, String catName, BigDecimal amount, String language) {
        return isEnglish(language)
                ? String.format("From %s to %s, you spent the most on %s: %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), catName, formatVnd(amount, language))
                : String.format("Trong khoảng %s đến %s, bạn chi nhiều nhất vào %s: %s.",
                start.format(DATE_FMT), end.format(DATE_FMT), catName, formatVnd(amount, language));
    }

    public String percentageReply(LocalDate start, LocalDate end,
                                   java.util.List<Map.Entry<String, BigDecimal>> breakdown,
                                   BigDecimal total, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append(isEnglish(language)
                ? String.format("Spending breakdown from %s to %s:\n", start.format(DATE_FMT), end.format(DATE_FMT))
                : String.format("Tỷ lệ chi tiêu từ %s đến %s:\n", start.format(DATE_FMT), end.format(DATE_FMT)));

        for (var entry : breakdown) {
            BigDecimal pct = entry.getValue().multiply(new BigDecimal("100"))
                    .divide(total, 1, RoundingMode.HALF_UP);
            sb.append(String.format("- %s: %s%% (%s)\n", entry.getKey(), pct, formatVnd(entry.getValue(), language)));
        }
        return sb.toString().trim();
    }

    public String trendReply(LocalDate prevStart, LocalDate prevEnd, String label,
                              String trend, BigDecimal currentTotal, BigDecimal prevTotal, String language) {
        return isEnglish(language)
                ? String.format("Compared to the previous period (%s to %s), your total %s is %s. (Current: %s, Previous: %s)",
                prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend,
                formatVnd(currentTotal, language), formatVnd(prevTotal, language))
                : String.format("So với giai đoạn trước (%s đến %s), tổng %s của bạn %s. (Hiện tại: %s, Trước đó: %s)",
                prevStart.format(DATE_FMT), prevEnd.format(DATE_FMT), label, trend,
                formatVnd(currentTotal, language), formatVnd(prevTotal, language));
    }

    public String noTransactions(String language) {
        return t(language,
                "Không có giao dịch nào trong khoảng thời gian này.",
                "No transactions found in this period.");
    }

    public String noDataForPercentage(String language) {
        return t(language,
                "Không có dữ liệu để tính tỷ lệ.",
                "No data available to calculate percentages.");
    }

    public String noDataForTopCategory(String language) {
        return t(language,
                "Chưa có dữ liệu để tính nhóm chi tiêu lớn nhất.",
                "No data available to determine the top spending category.");
    }

    // ═════════════════════════════════════════════════════════
    // UPDATE / DELETE RESPONSES
    // ═════════════════════════════════════════════════════════

    public String updateSuccess(BigDecimal amount, String note, String language) {
        return t(language,
                "Đã cập nhật giao dịch: " + formatVnd(amount, language) + " - " + note,
                "Updated transaction: " + formatVnd(amount, language) + " - " + note);
    }

    public String updateNotFound(String language) {
        return t(language,
                "Mình không tìm thấy giao dịch nào khớp để sửa.",
                "I couldn't find a matching transaction to update.");
    }

    public String updateMultipleMatches(String language) {
        return t(language,
                "Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: ngày hoặc số tiền cụ thể).",
                "Multiple transactions match. Please be more specific (e.g., date or amount).");
    }

    public String updateWhatToChange(String language) {
        return t(language,
                "Bạn muốn sửa thông tin gì của giao dịch này?",
                "What would you like to update for this transaction?");
    }

    public String deleteSuccess(int count, String detail, String language) {
        if (count == 1 && detail != null) {
            return t(language,
                    "Đã xóa giao dịch: " + detail,
                    "Deleted transaction: " + detail);
        }
        return t(language,
                "Đã xóa " + count + " giao dịch khớp với yêu cầu.",
                "Deleted " + count + " matching transactions.");
    }

    public String deleteNotFound(String language) {
        return t(language,
                "Mình không tìm thấy giao dịch nào khớp để xóa.",
                "I couldn't find a matching transaction to delete.");
    }

    public String deleteMultipleMatches(String language) {
        return t(language,
                "Có nhiều giao dịch khớp, bạn hãy nói rõ hơn (ví dụ: 'Xóa tất cả' hoặc số tiền cụ thể).",
                "Multiple transactions match. Please be more specific (e.g., 'delete all' or a specific amount).");
    }

    // ═════════════════════════════════════════════════════════
    // BUDGET STATUS RESPONSES
    // ═════════════════════════════════════════════════════════

    public String budgetStatusReply(BudgetStatusResult status, String language) {
        if ("INCOME_TARGET".equals(status.getPlanType())) {
            // Income target logic
            if (status.isOverBudget()) {
                // "overBudget" means "achieved" for income targets
                return t(language,
                        String.format("🎉 Tuyệt vời! Bạn đã đạt mục tiêu thu từ %s! (Mục tiêu: %s, Đã thu: %s — %s%%)",
                                status.getCategoryName(), formatVnd(status.getBudgetAmount(), language),
                                formatVnd(status.getSpentAmount(), language), status.getPercentUsed()),
                        String.format("🎉 Great! You've achieved your %s income target! (Target: %s, Earned: %s — %s%%)",
                                status.getCategoryName(), formatVnd(status.getBudgetAmount(), language),
                                formatVnd(status.getSpentAmount(), language), status.getPercentUsed()),
                        String.format("🎉 素晴らしい！%sの収入目標を達成しました！（目標：%s、現在の収得額：%s — %s%%）",
                                status.getCategoryName(), formatVnd(status.getBudgetAmount(), language),
                                formatVnd(status.getSpentAmount(), language), status.getPercentUsed()),
                        String.format("🎉 대단해요! %s 수입 목표를 달성했습니다! (목표: %s, 현재 수입: %s — %s%%)",
                                status.getCategoryName(), formatVnd(status.getBudgetAmount(), language),
                                formatVnd(status.getSpentAmount(), language), status.getPercentUsed()),
                        String.format("🎉 太棒了！您已达成 %s 的收入目标！（目标：%s，已收入：%s — %s%%）",
                                status.getCategoryName(), formatVnd(status.getBudgetAmount(), language),
                                formatVnd(status.getSpentAmount(), language), status.getPercentUsed())
                );
            }
            return t(language,
                    String.format("🎯 Mục tiêu thu %s: đã đạt %s%% (%s / %s)",
                            status.getCategoryName(), status.getPercentUsed(),
                            formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                    String.format("🎯 %s income target: %s%% achieved (%s / %s)",
                            status.getCategoryName(), status.getPercentUsed(),
                            formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                    String.format("🎯 %sの収入目標：%s%% 達成（%s / %s）",
                            status.getCategoryName(), status.getPercentUsed(),
                            formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                    String.format("🎯 %s 수입 목표: %s%% 달성 (%s / %s)",
                            status.getCategoryName(), status.getPercentUsed(),
                            formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                    String.format("🎯 %s 收入目标：已达成 %s%%（%s / %s）",
                            status.getCategoryName(), status.getPercentUsed(),
                            formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language))
            );
        }

        // Expense budget logic
        if (status.isOverBudget()) {
            return t(language,
                    String.format("⚠️ Bạn đã vượt ngân sách %s %s! (Ngân sách: %s, Đã chi: %s — %s%%)",
                            status.getCategoryName(), formatVnd(status.getOverAmount(), language),
                            formatVnd(status.getBudgetAmount(), language), formatVnd(status.getSpentAmount(), language),
                            status.getPercentUsed()),
                    String.format("⚠️ You've exceeded your %s budget by %s! (Budget: %s, Spent: %s — %s%% used)",
                            status.getCategoryName(), formatVnd(status.getOverAmount(), language),
                            formatVnd(status.getBudgetAmount(), language), formatVnd(status.getSpentAmount(), language),
                            status.getPercentUsed()),
                    String.format("⚠️ %sの予算を%s超過しました！（予算：%s、支出：%s — %s%%使用）",
                            status.getCategoryName(), formatVnd(status.getOverAmount(), language),
                            formatVnd(status.getBudgetAmount(), language), formatVnd(status.getSpentAmount(), language),
                            status.getPercentUsed()),
                    String.format("⚠️ %s 예산을 %s 초과했습니다! (예산: %s, 지출: %s — %s%% 사용)",
                            status.getCategoryName(), formatVnd(status.getOverAmount(), language),
                            formatVnd(status.getBudgetAmount(), language), formatVnd(status.getSpentAmount(), language),
                            status.getPercentUsed()),
                    String.format("⚠️ 您已超出 %s 预算 %s!（预算：%s，已支出：%s — 已使用 %s%%）",
                            status.getCategoryName(), formatVnd(status.getOverAmount(), language),
                            formatVnd(status.getBudgetAmount(), language), formatVnd(status.getSpentAmount(), language),
                            status.getPercentUsed())
            );
        }
        return t(language,
                String.format("📊 Ngân sách %s: đã dùng %s%% (%s / %s)",
                        status.getCategoryName(), status.getPercentUsed(),
                        formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                String.format("📊 %s budget: %s%% used (%s / %s)",
                        status.getCategoryName(), status.getPercentUsed(),
                        formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                String.format("📊 %sの予算：%s%% 使用（%s / %s）",
                        status.getCategoryName(), status.getPercentUsed(),
                        formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                String.format("📊 %s 예산: %s%% 사용 (%s / %s)",
                        status.getCategoryName(), status.getPercentUsed(),
                        formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language)),
                String.format("📊 %s 预算：已使用 %s%%（%s / %s）",
                        status.getCategoryName(), status.getPercentUsed(),
                        formatVnd(status.getSpentAmount(), language), formatVnd(status.getBudgetAmount(), language))
        );
    }

    public String allBudgetStatusReply(List<BudgetStatusResult> statuses, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append(t(language, "📋 Kế hoạch tài chính:\n", "📋 Financial Plan:\n"));

        // Separate expense budgets and income targets
        List<BudgetStatusResult> expenseBudgets = statuses.stream()
                .filter(s -> "EXPENSE_BUDGET".equals(s.getPlanType())).collect(java.util.stream.Collectors.toList());
        List<BudgetStatusResult> incomeTargets = statuses.stream()
                .filter(s -> "INCOME_TARGET".equals(s.getPlanType())).collect(java.util.stream.Collectors.toList());

        if (!expenseBudgets.isEmpty()) {
            sb.append(t(language, "\n💸 Ngân sách chi:\n", "\n💸 Expense Budgets:\n"));
            for (BudgetStatusResult s : expenseBudgets) {
                String emoji = s.isOverBudget() ? "🔴" : (s.getPercentUsed().compareTo(new BigDecimal("80")) >= 0 ? "🟡" : "🟢");
                sb.append(String.format("%s %s: %s%% (%s / %s)\n", emoji,
                        s.getCategoryName(), s.getPercentUsed(),
                        formatVnd(s.getSpentAmount(), language), formatVnd(s.getBudgetAmount(), language)));
            }
        }

        if (!incomeTargets.isEmpty()) {
            sb.append(t(language, "\n💰 Mục tiêu thu:\n", "\n💰 Income Targets:\n"));
            for (BudgetStatusResult s : incomeTargets) {
                String emoji = s.isOverBudget() ? "🎉" : (s.getPercentUsed().compareTo(new BigDecimal("70")) >= 0 ? "🟡" : "🔵");
                sb.append(String.format("%s %s: %s%% (%s / %s)\n", emoji,
                        s.getCategoryName(), s.getPercentUsed(),
                        formatVnd(s.getSpentAmount(), language), formatVnd(s.getBudgetAmount(), language)));
            }
        }

        return sb.toString().trim();
    }

    public String noBudgetData(String language) {
        return t(language,
                "Bạn chưa đặt kế hoạch tài chính nào (ngân sách chi hoặc mục tiêu thu). Bạn có muốn thiết lập không?",
                "You haven't set any financial plans (expense budgets or income targets) yet. Would you like to set one up?",
                "財務計画（支出予算または収入目標）がまだ設定されていません。設定しますか？",
                "아직 재무 계획(지출 예산 또는 수입 목표)이 설정되지 않았습니다. 설정하시겠습니까?",
                "您尚未设置任何财务计划（支出预算或收入目标）。您想要设置一个吗？"
        );
    }

    // ═════════════════════════════════════════════════════════
    // OVERSPENDING ALERT RESPONSES
    // ═════════════════════════════════════════════════════════

    public String overspendingAlertReply(List<OverspendingAlert> alerts, String language) {
        if (alerts.isEmpty()) {
            return t(language,
                    "✅ Chi tiêu của bạn bình thường, không có khoản nào tăng bất thường.",
                    "✅ Your spending looks normal — no unusual increases detected.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t(language, "⚠️ Cảnh báo chi tiêu bất thường:\n", "⚠️ Overspending Alerts:\n"));
        for (OverspendingAlert a : alerts) {
            sb.append(isEnglish(language)
                    ? String.format("• %s: up %s%% (%s vs %s previously)\n",
                    a.getCategoryName(), a.getPercentIncrease(),
                    formatVnd(a.getCurrentAmount(), language), formatVnd(a.getPreviousAmount(), language))
                    : String.format("• %s: tăng %s%% (%s so với %s trước đó)\n",
                    a.getCategoryName(), a.getPercentIncrease(),
                    formatVnd(a.getCurrentAmount(), language), formatVnd(a.getPreviousAmount(), language)));
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════
    // MONTHLY SUMMARY RESPONSES
    // ═════════════════════════════════════════════════════════

    public String monthlySummaryReply(MonthlySummaryResult summary, int month, int year, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append(isEnglish(language)
                ? String.format("📊 Monthly Summary (%d/%d):\n", month, year)
                : String.format("📊 Tổng kết tháng %d/%d:\n", month, year));

        sb.append(isEnglish(language)
                ? String.format("💰 Total Income: %s\n", formatVnd(summary.getTotalIncome(), language))
                : String.format("💰 Tổng thu: %s\n", formatVnd(summary.getTotalIncome(), language)));
        sb.append(isEnglish(language)
                ? String.format("💸 Total Expense: %s\n", formatVnd(summary.getTotalExpense(), language))
                : String.format("💸 Tổng chi: %s\n", formatVnd(summary.getTotalExpense(), language)));

        // Trend vs previous month
        if (!"STABLE".equals(summary.getTrendDirection())) {
            String arrow = "UP".equals(summary.getTrendDirection()) ? "📈" : "📉";
            sb.append(isEnglish(language)
                    ? String.format("%s %s%% vs last month\n", arrow, summary.getTrendPercent().abs())
                    : String.format("%s %s%% so với tháng trước\n", arrow, summary.getTrendPercent().abs()));
        }

        // Category breakdown
        if (summary.getCategoryBreakdowns() != null && !summary.getCategoryBreakdowns().isEmpty()) {
            sb.append(t(language, "\n📂 Phân bổ chi tiêu:\n", "\n📂 Spending Breakdown:\n"));
            for (CategoryPercentage cp : summary.getCategoryBreakdowns()) {
                sb.append(String.format("  • %s: %s%% (%s)\n",
                        cp.getCategoryName(), cp.getPercentage(), formatVnd(cp.getAmount(), language)));
            }
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════
    // FINANCIAL HEALTH RESPONSES
    // ═════════════════════════════════════════════════════════

    public String financialHealthReply(FinancialHealthResult health, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append(t(language, "🏥 Sức khỏe tài chính:\n", "🏥 Financial Health:\n"));
        sb.append(isEnglish(language)
                ? String.format("• Income: %s\n", formatVnd(health.getTotalIncome(), language))
                : String.format("• Thu nhập: %s\n", formatVnd(health.getTotalIncome(), language)));
        sb.append(isEnglish(language)
                ? String.format("• Expense: %s\n", formatVnd(health.getTotalExpense(), language))
                : String.format("• Chi tiêu: %s\n", formatVnd(health.getTotalExpense(), language)));
        sb.append(isEnglish(language)
                ? String.format("• Savings Rate: %s%%\n", health.getSavingsRate())
                : String.format("• Tỷ lệ tiết kiệm: %s%%\n", health.getSavingsRate()));
        sb.append(isEnglish(language)
                ? String.format("• Expense-to-Income: %s%%", health.getExpenseToIncomeRatio())
                : String.format("• Chi/Thu: %s%%", health.getExpenseToIncomeRatio()));
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════
    // WEEKLY PATTERN RESPONSES
    // ═════════════════════════════════════════════════════════

    public String weeklyPatternReply(WeeklyPatternResult pattern, String language) {
        String peakDayName = translateDayOfWeek(pattern.getPeakDay(), language);
        StringBuilder sb = new StringBuilder();
        sb.append(t(language, "📅 Phân tích chi tiêu theo tuần:\n", "📅 Weekly Spending Pattern:\n"));
        sb.append(isEnglish(language)
                ? String.format("• Weekday avg: %s\n", formatVnd(pattern.getWeekdayAverage(), language))
                : String.format("• Trung bình ngày thường: %s\n", formatVnd(pattern.getWeekdayAverage(), language)));
        sb.append(isEnglish(language)
                ? String.format("• Weekend avg: %s\n", formatVnd(pattern.getWeekendAverage(), language))
                : String.format("• Trung bình cuối tuần: %s\n", formatVnd(pattern.getWeekendAverage(), language)));
        sb.append(isEnglish(language)
                ? String.format("• Peak day: %s\n", peakDayName)
                : String.format("• Ngày chi nhiều nhất: %s\n", peakDayName));
        if (pattern.isSpendsMoreOnWeekends()) {
            sb.append(t(language,
                    "💡 Bạn thường chi nhiều hơn vào cuối tuần.",
                    "💡 You tend to spend more on weekends."));
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════
    // SMART SUGGESTION RESPONSES
    // ═════════════════════════════════════════════════════════

    public String smartSuggestionReply(List<SmartSuggestionResult> suggestions, String language) {
        if (suggestions.isEmpty()) {
            return t(language,
                    "👍 Chi tiêu của bạn hợp lý, chưa có gợi ý cải thiện.",
                    "👍 Your spending looks reasonable — no suggestions at this time.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t(language, "💡 Gợi ý tiết kiệm:\n", "💡 Smart Suggestions:\n"));
        for (SmartSuggestionResult s : suggestions) {
            sb.append(isEnglish(language)
                    ? String.format("• Reduce %s by 15%% → save ~%s/month\n",
                    s.getCategoryName(), formatVnd(s.getPotentialSavings(), language))
                    : String.format("• Giảm %s 15%% → tiết kiệm ~%s/tháng\n",
                    s.getCategoryName(), formatVnd(s.getPotentialSavings(), language)));
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════
    // FINANCIAL SCORE RESPONSES
    // ═════════════════════════════════════════════════════════

    public String financialScoreReply(FinancialScoreResult score, String language) {
        String gradeEmoji = switch (score.getGrade()) {
            case "A" -> "🏆";
            case "B" -> "✅";
            case "C" -> "📊";
            case "D" -> "⚠️";
            default -> "🔴";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(isEnglish(language)
                ? String.format("%s Financial Score: %d/100 (Grade %s)\n", gradeEmoji, score.getTotalScore(), score.getGrade())
                : String.format("%s Điểm tài chính: %d/100 (Hạng %s)\n", gradeEmoji, score.getTotalScore(), score.getGrade()));
        sb.append(isEnglish(language)
                ? String.format("• Savings Rate: %d/30\n", score.getSavingsRateScore())
                : String.format("• Tỷ lệ tiết kiệm: %d/30\n", score.getSavingsRateScore()));
        sb.append(isEnglish(language)
                ? String.format("• Spending Stability: %d/25\n", score.getStabilityScore())
                : String.format("• Ổn định chi tiêu: %d/25\n", score.getStabilityScore()));
        sb.append(isEnglish(language)
                ? String.format("• Budget Adherence: %d/25\n", score.getBudgetAdherenceScore())
                : String.format("• Tuân thủ ngân sách: %d/25\n", score.getBudgetAdherenceScore()));
        sb.append(isEnglish(language)
                ? String.format("• Income Consistency: %d/20\n", score.getIncomeConsistencyScore())
                : String.format("• Thu nhập ổn định: %d/20\n", score.getIncomeConsistencyScore()));

        if (score.getSavingsRate() != null) {
            sb.append(isEnglish(language)
                    ? String.format("\n💰 Actual savings rate: %s%%", score.getSavingsRate())
                    : String.format("\n💰 Tỷ lệ tiết kiệm thực tế: %s%%", score.getSavingsRate()));
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════
    // GENERIC / ERROR RESPONSES
    // ═════════════════════════════════════════════════════════

    public String unknownMessage(String language) {
        return t(language,
                "Mình chưa hiểu rõ ý bạn. Hãy thử: \"Tháng này tôi tiêu nhiều nhất vào cái gì?\" hoặc \"Hôm nay tôi ăn phở 45k\".",
                "I didn't quite understand. Try: \"What did I spend the most on this month?\" or \"I had pho for 45k today\".");
    }

    public String emptyInputMessage(String language) {
        return t(language,
                "Bạn hãy nhập câu hỏi hoặc nội dung chi tiêu để mình xử lý nhé.",
                "Please enter a question or a transaction so I can help.");
    }

    public String aiConnectionError(String language) {
        return t(language,
                "Mình chưa hiểu rõ ý bạn. Bạn có thể nói rõ hơn một chút không?",
                "I'm not sure I understand. Could you please be more specific?");
    }

    public String noCategories(String language) {
        return t(language,
                "Chưa có danh mục chi tiêu nào. Hãy tạo danh mục trước nhé.",
                "No categories found. Please create a category first.");
    }

    public String noAccount(String language) {
        return t(language,
                "Bạn chưa có tài khoản/ví. Hãy tạo tài khoản trước khi thêm giao dịch nhé.",
                "You don't have any wallet/account yet. Please create one before adding a transaction.");
    }

    public String needAccountSelection(String language) {
        return t(language,
                "Bạn muốn dùng ví nào cho giao dịch này?",
                "Which wallet should I use for this transaction?");
    }

    /** Trim note for display (max 80 chars). */
    public String trimNote(String note) {
        if (note == null) return "";
        String trimmed = note.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }

    // ═════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════

    private String translateDayOfWeek(DayOfWeek day, String language) {
        if ("en".equals(language)) return day.name().charAt(0) + day.name().substring(1).toLowerCase();
        return switch (language) {
            case "ja" -> switch (day) {
                case MONDAY -> "月曜日"; case TUESDAY -> "火曜日"; case WEDNESDAY -> "水曜日";
                case THURSDAY -> "木曜日"; case FRIDAY -> "金曜日"; case SATURDAY -> "土曜日"; case SUNDAY -> "日曜日";
            };
            case "ko" -> switch (day) {
                case MONDAY -> "월요일"; case TUESDAY -> "화요일"; case WEDNESDAY -> "수요일";
                case THURSDAY -> "목요일"; case FRIDAY -> "금요일"; case SATURDAY -> "토요일"; case SUNDAY -> "일요일";
            };
            case "zh" -> switch (day) {
                case MONDAY -> "星期一"; case TUESDAY -> "星期二"; case WEDNESDAY -> "星期三";
                case THURSDAY -> "星期四"; case FRIDAY -> "星期五"; case SATURDAY -> "星期六"; case SUNDAY -> "星期日";
            };
            default -> switch (day) {
                case MONDAY -> "Thứ Hai"; case TUESDAY -> "Thứ Ba"; case WEDNESDAY -> "Thứ Tư";
                case THURSDAY -> "Thứ Năm"; case FRIDAY -> "Thứ Sáu"; case SATURDAY -> "Thứ Bảy"; case SUNDAY -> "Chủ Nhật";
            };
        };
    }
}
