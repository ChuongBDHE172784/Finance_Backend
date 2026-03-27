package com.example.finance_backend.service.assistant.parser;

import com.example.finance_backend.service.ai.TextPreprocessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/**
 * Lớp bao bọc (Wrapper) cho việc trích xuất số tiền từ văn bản.
 * Sử dụng logic từ TextPreprocessor để xử lý các con số và đơn vị tiền tệ.
 */
@Component
@RequiredArgsConstructor
public class MoneyParser {
    private final TextPreprocessor textPreprocessor;

    /** Trích xuất một số tiền duy nhất từ chuỗi văn bản cho trước. */
    public BigDecimal extractSingleAmount(String text) {
        return textPreprocessor.extractSingleAmount(text);
    }
}
