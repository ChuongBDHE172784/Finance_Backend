package com.example.finance_backend.service.assistant.parser;

import org.springframework.stereotype.Component;

/**
 * Hỗ trợ làm sạch các từ khóa (Keywords) bằng cách loại bỏ các từ dư thừa
 * và nhận diện các lệnh nhanh như 'đồng ý', 'hủy', 'lưu'.
 */
@Component
public class KeywordCleaner {

    /** Loại bỏ các từ dừng (stop-words) và các ký tự đặc biệt để lấy từ khóa chính. */
    public String cleanKeywords(String keywords) {
        if (keywords == null) return "";
        return keywords
                .replaceAll(
                        "\\b(sua|doi|cap nhat|thanh|sang|thay|den|xoa|huy|bo|giup|giao dich|khoan|cai|nay|tat ca|het|hom nay|hom qua|ngay|thang|nam|vi|momo|tien|chi|tieu|vua|nay|chieu|sang|toi|update|change|edit|set|delete|remove|transaction|entry|this|that|all|everything|today|yesterday|day|month|year|wallet|money|expense|income|spend|spent|buy|payment|pay|recent|nap|nap tien|gui|rut|banking|transfer)\\b",
                        "")
                .replaceAll("[0-9.,dđkK]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public boolean isRuleBasedConfirmation(String normalized) {
        if (normalized == null || normalized.isBlank())
            return false;
        // Don't confirm if there are amounts (likely a new transaction)
        if (normalized.matches(".*\\d+.*"))
            return false;

        String s = normalized.trim().toLowerCase();
        return s.equals("ok") || s.equals("luu") || s.equals("dung")
                || s.equals("dong y") || s.equals("yes") || s.equals("save")
                || s.equals("confirm") || s.equals("chinh xac") || s.equals("duyet")
                || s.equals("xac nhan") || s.equals("co") || s.equals("vang")
                || s.equals("u tru") || s.equals("chuan") || s.equals("uy") || s.equals("chot");
    }

    public boolean isCancelRequest(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        String s = normalized.trim().toLowerCase();
        return s.startsWith("huy") || s.startsWith("bo qua") || s.equals("khong") 
            || s.equals("khong phai") || s.equals("ngung") || s.equals("cancel") 
            || s.equals("stop") || s.equals("no") || s.equals("nevermind");
    }
}
