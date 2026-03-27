package com.example.finance_backend.service.assistant.parser;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Hỗ trợ chuyển đổi các chuỗi ngày tháng (đặc biệt là định dạng ISO do AI trả về) 
 * thành đối tượng LocalDate để xử lý dữ liệu.
 */
@Component
public class DateParser {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Phân tích chuỗi ngày và trả về LocalDate, hoặc giá trị mặc định nếu lỗi. */
    public LocalDate parseDate(String date, LocalDate fallback) {
        if (date == null || date.isBlank())
            return fallback;
        try {
            return LocalDate.parse(date.trim(), DATE_FMT);
        } catch (Exception e) {
            return fallback;
        }
    }
}
