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
 * Quản lý ngữ cảnh hội thoại cho các tương tác nhiều lượt.
 * Xử lý: điền thông tin còn thiếu từ lịch sử, phát hiện nhu cầu làm rõ,
 * và giải quyết các tham chiếu mơ hồ.
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
     * Cố gắng điền các thông tin (slots) còn thiếu bằng cách sử dụng lịch sử hội thoại.
     * Ví dụ: Người dùng nói "ăn trưa", bot hỏi "bao nhiêu?", người dùng nói "45k"
     * → điền thông tin số tiền từ câu trả lời "45k".
     */
    public List<TransactionSlot> resolveWithContext(List<TransactionSlot> slots,
                                                     IntentResult intent,
                                                     ParsedMessage currentMessage,
                                                     List<AiMessage> history) {
        if (history == null || history.isEmpty()) return slots;

        // Nếu tin nhắn hiện tại rất ngắn (có khả năng là câu trả lời tiếp theo),
        // hãy thử kết hợp với ngữ cảnh trước đó
        if (shouldUseHistory(currentMessage, slots)) {
            String lastUserMsg = getLastUserMessage(history);
            if (lastUserMsg != null && !lastUserMsg.isBlank()) {
                // Kết hợp ngữ cảnh: ghi chú trước đó + số tiền hiện tại
                for (int i = 0; i < slots.size(); i++) {
                    TransactionSlot slot = slots.get(i);
                    if (slot.getAmount() != null && (slot.getNote() == null || slot.getNote().isBlank()
                            || slot.getNote().length() <= 5)) {
                        // Tin nhắn hiện tại có số tiền nhưng không có ghi chú ý nghĩa → lấy ghi chú từ lịch sử
                        slot.setNote(lastUserMsg.trim());
                        String normalized = textPreprocessor.normalizeVietnamese(lastUserMsg);
                        if (slot.getCategoryName() == null) {
                            slot.setCategoryName(entityExtractor.inferCategory(normalized));
                        }
                    } else if (slot.getAmount() == null && slot.getNote() != null) {
                        // Tin nhắn hiện tại có ghi chú nhưng không có số tiền → thử trích xuất từ lịch sử
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
     * Kiểm tra xem tin nhắn hiện tại có cần câu hỏi làm rõ hay không.
     * Trả về chuỗi câu hỏi làm rõ, hoặc null nếu không cần thiết.
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
     * Giải quyết các tham chiếu mơ hồ như "xóa cái đó", "sửa cái hồi nãy".
     * Trả về các từ khóa được trích xuất từ tin nhắn ngữ cảnh có liên quan cuối cùng.
     */
    public String resolveAmbiguousReference(String normalizedText, List<AiMessage> history) {
        if (history == null || history.isEmpty()) return null;

        boolean isAmbiguous = TextPreprocessor.containsAny(normalizedText,
                "cai do", "cai nay", "cai kia", "hoi nay", "vua roi", "vua nay",
                "that one", "this one", "the last one", "just now", "recent");

        if (!isAmbiguous) return null;

        // Tìm tin nhắn cuối cùng của trợ lý có đề cập đến một giao dịch
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

        // Dự phòng bằng tin nhắn cuối cùng của người dùng
        String lastUser = getLastUserMessage(history);
        return lastUser;
    }

    // ═════════════════════════════════════════════════════════
    // CÁC PHƯƠNG THỨC HỖ TRỢ RIÊNG
    // ═════════════════════════════════════════════════════════

    private boolean shouldUseHistory(ParsedMessage current, List<TransactionSlot> slots) {
        // Các tin nhắn ngắn (có khả năng là câu trả lời tiếp theo) hoặc các tin nhắn có số tiền nhưng không có ghi chú
        String text = current.getNormalizedText();
        if (text == null) return false;
        if (text.length() <= 12) return true;

        // Có số tiền nhưng không có ghi chú ý nghĩa → có khả năng đang trả lời câu hỏi "bao nhiêu tiền?"
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
