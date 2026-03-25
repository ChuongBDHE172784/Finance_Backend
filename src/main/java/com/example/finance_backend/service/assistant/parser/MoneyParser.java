package com.example.finance_backend.service.assistant.parser;

import com.example.finance_backend.service.ai.TextPreprocessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class MoneyParser {
    private final TextPreprocessor textPreprocessor;

    public BigDecimal extractSingleAmount(String text) {
        return textPreprocessor.extractSingleAmount(text);
    }
}
