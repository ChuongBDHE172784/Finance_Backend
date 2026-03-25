package com.example.finance_backend.service.assistant.parser;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class DateParser {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

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
