package com.example.finance_backend.service.ai;

import com.example.finance_backend.dto.IntentResult;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.dto.ParsedMessage;
import com.example.finance_backend.dto.TransactionSlot;
import com.example.finance_backend.entity.AiMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Manages conversation context for multi-turn interactions.
 * Handles: slot filling from history, clarification detection,
 * and ambiguous reference resolution.
 */
@Component
public class ConversationContextManager {

    private final TextPreprocessor textPreprocessor;
    private final EntityExtractor entityExtractor;

    public ConversationContextManager(TextPreprocessor textPreprocessor, EntityExtractor entityExtractor) {
        this.textPreprocessor = textPreprocessor;
        this.entityExtractor = entityExtractor;
    }

    /**
     * Attempts to fill missing slots using conversation history.
     * Example: User says "ăn trưa", bot asks "bao nhiêu?", user says "45k"
     * → fills the amount slot from the follow-up "45k".
     */
    public List<TransactionSlot> resolveWithContext(List<TransactionSlot> slots,
                                                     IntentResult intent,
                                                     ParsedMessage currentMessage,
                                                     List<AiMessage> history) {
        if (history == null || history.isEmpty()) return slots;

        // If current message is very short (likely a follow-up answer),
        // try to combine with previous context
        if (shouldUseHistory(currentMessage, slots)) {
            String lastUserMsg = getLastUserMessage(history);
            if (lastUserMsg != null && !lastUserMsg.isBlank()) {
                // Merge context: previous note + current amount
                for (int i = 0; i < slots.size(); i++) {
                    TransactionSlot slot = slots.get(i);
                    if (slot.getAmount() != null && (slot.getNote() == null || slot.getNote().isBlank()
                            || slot.getNote().length() <= 5)) {
                        // Current message has amount but no meaningful note → take note from history
                        slot.setNote(lastUserMsg.trim());
                        String normalized = textPreprocessor.normalizeVietnamese(lastUserMsg);
                        if (slot.getCategoryName() == null) {
                            slot.setCategoryName(entityExtractor.inferCategory(normalized));
                        }
                    } else if (slot.getAmount() == null && slot.getNote() != null) {
                        // Current message has note but no amount → try extracting from history
                        BigDecimal historyAmount = textPreprocessor.extractSingleAmount(lastUserMsg);
                        if (historyAmount != null) {
                            slot.setAmount(historyAmount);
                        }
                    }
                }
            }
        }
        return slots;
    }

    /**
     * Checks if the current message needs a clarification question.
     * Returns a clarification question string, or null if not needed.
     */
    public String detectClarificationNeeded(List<TransactionSlot> slots, IntentResult intent, String language) {
        if (intent.getIntent() != Intent.INSERT_TRANSACTION) return null;

        for (TransactionSlot slot : slots) {
            if (slot.isMissingCriticalInfo()) {
                String note = slot.getNote() != null ? slot.getNote().trim() : "";
                if (note.isBlank()) {
                    return isEnglish(language)
                            ? "What did you spend on and how much?"
                            : "Bạn chi tiêu cho việc gì và bao nhiêu tiền?";
                }
                return isEnglish(language)
                        ? "How much did you spend on \"" + note + "\"?"
                        : "Bạn " + note + " hết bao nhiêu tiền?";
            }
        }
        return null;
    }

    /**
     * Resolves ambiguous references like "xóa cái đó", "sửa cái hồi nãy".
     * Returns keywords extracted from the last relevant context message.
     */
    public String resolveAmbiguousReference(String normalizedText, List<AiMessage> history) {
        if (history == null || history.isEmpty()) return null;

        boolean isAmbiguous = TextPreprocessor.containsAny(normalizedText,
                "cai do", "cai nay", "cai kia", "hoi nay", "vua roi", "vua nay",
                "that one", "this one", "the last one", "just now", "recent");

        if (!isAmbiguous) return null;

        // Look for the last assistant message that mentions a transaction
        for (int i = history.size() - 1; i >= 0; i--) {
            AiMessage msg = history.get(i);
            if ("ASSISTANT".equalsIgnoreCase(msg.getRole())) {
                String content = msg.getContent();
                if (content != null && (content.contains("đ") || content.contains("VND")
                        || content.contains("giao dịch") || content.contains("transaction"))) {
                    return content;
                }
            }
        }

        // Fall back to last user message
        String lastUser = getLastUserMessage(history);
        return lastUser;
    }

    // ═════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════

    private boolean shouldUseHistory(ParsedMessage current, List<TransactionSlot> slots) {
        // Short messages (likely follow-up answers) or messages with amount but no note
        String text = current.getNormalizedText();
        if (text == null) return false;
        if (text.length() <= 12) return true;

        // Has amount but no meaningful note → probably answering a "how much?" question
        boolean hasAmountOnly = current.hasAmounts()
                && slots.stream().allMatch(s -> s.getNote() == null || s.getNote().length() <= 5);
        if (hasAmountOnly) return true;

        return TextPreprocessor.containsAny(text,
                "con hom qua", "cai nao", "nhieu nhat", "luc nao", "con nua", "con khong",
                "yesterday", "today", "which one", "most", "any more", "anything else");
    }

    private String getLastUserMessage(List<AiMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            AiMessage m = history.get(i);
            if (m.getRole() != null && m.getRole().equalsIgnoreCase("USER")) {
                return m.getContent();
            }
        }
        return null;
    }

    private boolean isEnglish(String language) {
        return "en".equals(language);
    }
}
