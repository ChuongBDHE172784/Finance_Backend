package com.example.finance_backend.service.assistant.state;

import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.service.assistant.parser.DraftLineParser;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParsedEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ConversationStateService {

    private final DraftLineParser draftLineParser;

    public static class PendingPlanAction {
        public String intent;
        public String category;
        public BigDecimal amount;
        public String month;
        public String awaitingField;
    }

    public static class PendingScheduleAction {
        public String categoryName;
        public Long categoryId;
        public BigDecimal amount;
        public Long accountId;
        public String repeatType;
        public String repeatConfig;
        public String note;
        public String awaitingField; // CATEGORY, AMOUNT, ACCOUNT, REPEAT
    }

    private final Map<String, PendingPlanAction> planningState = new ConcurrentHashMap<>();
    private final Map<String, PendingScheduleAction> schedulePlanningState = new ConcurrentHashMap<>();

    public PendingPlanAction getPlanningState(String conversationId) {
        return planningState.get(conversationId);
    }

    public void setPlanningState(String conversationId, PendingPlanAction state) {
        planningState.put(conversationId, state);
    }

    public PendingPlanAction computePlanningStateIfAbsent(String conversationId, java.util.function.Function<String, PendingPlanAction> mappingFunction) {
        return planningState.computeIfAbsent(conversationId, mappingFunction);
    }

    public void removePlanningState(String conversationId) {
        planningState.remove(conversationId);
    }

    public PendingScheduleAction getSchedulePlanningState(String conversationId) {
        return schedulePlanningState.get(conversationId);
    }

    public void setSchedulePlanningState(String conversationId, PendingScheduleAction state) {
        schedulePlanningState.put(conversationId, state);
    }

    public PendingScheduleAction computeSchedulePlanningStateIfAbsent(String conversationId, java.util.function.Function<String, PendingScheduleAction> mappingFunction) {
        return schedulePlanningState.computeIfAbsent(conversationId, mappingFunction);
    }

    public void removeSchedulePlanningState(String conversationId) {
        schedulePlanningState.remove(conversationId);
    }

    public void clearAllStates(String conversationId) {
        planningState.remove(conversationId);
        schedulePlanningState.remove(conversationId);
    }

    public List<GeminiParsedEntry> extractDraftEntriesFromHistory(List<AiMessage> history) {
        if (history == null || history.isEmpty())
            return new ArrayList<>();

        // Tìm tin nhắn cuối cùng của Assistant là bản nháp
        for (int i = history.size() - 1; i >= 0; i--) {
            AiMessage m = history.get(i);
            if ("ASSISTANT".equalsIgnoreCase(m.getRole())) {
                String content = m.getContent();
                if (content == null) continue;

                // Nếu đã thấy một thông báo hoàn tất, nghĩa là các bản nháp trước đó đã được giải quyết.
                // Chúng ta dừng tìm kiếm để tránh gộp các giao dịch mới vào các giao dịch đã lưu.
                String trimmed = content.trim();
                if (trimmed.startsWith("Đã lưu") || trimmed.startsWith("Saved") || 
                    trimmed.startsWith("Đã cập nhật") || trimmed.startsWith("Updated") || 
                    trimmed.startsWith("Đã xóa") || trimmed.startsWith("Deleted")) {
                    break; 
                }

                if (content.contains("kiểm tra lại") || content.contains("lưu giao dịch này không")
                        || content.contains("review") || content.contains("save this transaction")) {
                    return draftLineParser.parseDraftLines(content);
                }
            }
        }
        return new ArrayList<>();
    }
}
