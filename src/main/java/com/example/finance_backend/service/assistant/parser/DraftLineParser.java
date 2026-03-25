package com.example.finance_backend.service.assistant.parser;

import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DraftLineParser {
    private final MoneyParser moneyParser;

    public List<GeminiParsedEntry> parseDraftLines(String content) {
        List<GeminiParsedEntry> result = new ArrayList<>();
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("- ") || line.startsWith("+ ")) {
                GeminiParsedEntry entry = parseDraftLine(line);
                if (entry != null)
                    result.add(entry);
            }
        }
        return result;
    }

    public GeminiParsedEntry parseDraftLine(String line) {
        try {
            GeminiParsedEntry entry = new GeminiParsedEntry();
            entry.type = line.startsWith("+") ? "INCOME" : "EXPENSE";

            // Loại bỏ tiền tố (- hoặc +)
            String parts = line.substring(2).trim();

            // Tìm số tiền và danh mục: "30.000 đ • Ăn uống (Ăn phở)"
            int dotIdx = parts.indexOf("•");
            if (dotIdx == -1)
                return null;

            String amountPart = parts.substring(0, dotIdx).trim();
            String categoryAndNote = parts.substring(dotIdx + 1).trim();

            // Extract amount using moneyParser
            entry.amount = moneyParser.extractSingleAmount(amountPart);
            if (entry.amount == null)
                return null;

            // Extract category and note: "Ăn uống (Ăn phở)"
            int bracketIdx = categoryAndNote.indexOf("(");
            if (bracketIdx != -1) {
                entry.categoryName = categoryAndNote.substring(0, bracketIdx).trim();
                String note = categoryAndNote.substring(bracketIdx + 1).trim();
                if (note.endsWith(")")) {
                    note = note.substring(0, note.length() - 1);
                }
                entry.note = note;
            } else {
                entry.categoryName = categoryAndNote.trim();
                entry.note = "";
            }
            return entry;
        } catch (Exception e) {
            return null;
        }
    }
}
