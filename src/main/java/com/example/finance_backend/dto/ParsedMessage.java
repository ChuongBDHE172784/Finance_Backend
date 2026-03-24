package com.example.finance_backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Kết quả của quá trình tiền xử lý văn bản. Chứa văn bản đã chuẩn hóa, các giá trị tiền tệ được trích xuất,
 * các ngày được phát hiện, và các tin nhắn con cho đầu vào chứa nhiều giao dịch.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedMessage {
    /** Tin nhắn gốc của người dùng */
    private String originalText;
    /** Văn bản đã chuẩn hóa (đã loại bỏ dấu tiếng Việt, chuyển thành chữ thường) */
    private String normalizedText;
    /** Ngôn ngữ được phát hiện: "vi" hoặc "en" */
    private String language;
    /** Tất cả các số tiền được trích xuất từ tin nhắn */
    private List<BigDecimal> extractedAmounts;
    /** Khoảng ngày được phát hiện trong tin nhắn */
    private LocalDate startDate;
    private LocalDate endDate;
    /** Các tin nhắn con cho đầu vào đa giao dịch (VD: "phở 45k, cà phê 30k" → 2 mục) */
    private List<String> subMessages;

    public boolean hasAmounts() {
        return extractedAmounts != null && !extractedAmounts.isEmpty();
    }

    public boolean isMultiTransaction() {
        return subMessages != null && subMessages.size() > 1;
    }

    public BigDecimal getFirstAmount() {
        return hasAmounts() ? extractedAmounts.get(0) : null;
    }
}
