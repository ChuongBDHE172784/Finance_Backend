package com.example.finance_backend.service.assistant.intent;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.dto.IntentResult.Intent;
import com.example.finance_backend.entity.AiMessage;
import com.example.finance_backend.entity.RepeatType;
import com.example.finance_backend.repository.AccountRepository;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.service.CategoryService;
import com.example.finance_backend.service.ScheduleService;
import com.example.finance_backend.service.ai.EntityExtractor;
import com.example.finance_backend.service.ai.GeminiClientWrapper.GeminiParseResult;
import com.example.finance_backend.service.ai.ResponseGenerator;
import com.example.finance_backend.service.ai.TextPreprocessor;
import com.example.finance_backend.service.assistant.parser.DateParser;
import com.example.finance_backend.service.assistant.parser.KeywordCleaner;
import com.example.finance_backend.service.assistant.state.ConversationStateService;
import com.example.finance_backend.service.assistant.state.ConversationStateService.PendingScheduleAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Handler xử lý các yêu cầu liên quan đến lập lịch chi tiêu định kỳ (Schedules).
 * Hỗ trợ tạo mới, cập nhật, bật/tắt, xóa và liệt kê các lịch trình sắp tới.
 */
@Component
public class ScheduleHandler extends BaseIntentHandler {

    private final ScheduleService scheduleService;
    private final ConversationStateService stateService;
    private final EntityExtractor entityExtractor;

    public ScheduleHandler(
            CategoryService categoryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            TextPreprocessor textPreprocessor,
            ResponseGenerator responseGenerator,
            DateParser dateParser,
            KeywordCleaner keywordCleaner,
            ScheduleService scheduleService,
            ConversationStateService stateService,
            EntityExtractor entityExtractor) {
        super(categoryService, accountRepository, categoryRepository, textPreprocessor, responseGenerator, dateParser, keywordCleaner);
        this.scheduleService = scheduleService;
        this.stateService = stateService;
        this.entityExtractor = entityExtractor;
    }

    @Override
    public List<Intent> getSupportedIntents() {
        return List.of(Intent.CREATE_SCHEDULE, Intent.UPDATE_SCHEDULE, Intent.DELETE_SCHEDULE, 
                       Intent.DISABLE_SCHEDULE, Intent.ENABLE_SCHEDULE, 
                       Intent.LIST_SCHEDULES, Intent.LIST_UPCOMING_TRANSACTIONS, 
                       Intent.EXPLAIN_TRANSACTION_SOURCE);
    }

    @Override
    public AiAssistantResponse handle(AiAssistantRequest request, ParsedMessage parsed, IntentResult intentResult, GeminiParseResult gemini, List<AiMessage> history) {
        String language = request.getLanguage();
        String normalized = parsed.getNormalizedText();
        Long userId = request.getUserId();
        Intent intent = intentResult.getIntent();

        // Check for Cancel
        if (keywordCleaner.cleanKeywords(normalized).isBlank() && (normalized.contains("huy") || normalized.contains("cancel") || normalized.contains("thoi"))) {
            stateService.removeSchedulePlanningState(request.getConversationId());
            return AiAssistantResponse.builder().intent("CANCEL").reply(responseGenerator.t(language, "Đã hủy thao tác tạo lịch.", "Scheduled creation cancelled.")).build();
        }

        if (Intent.LIST_UPCOMING_TRANSACTIONS.equals(intent)) return handleListUpcoming(userId, language);
        if (Intent.LIST_SCHEDULES.equals(intent)) return handleListSchedules(userId, language);
        if (Intent.EXPLAIN_TRANSACTION_SOURCE.equals(intent)) return handleExplainSource(normalized, language);
        if (Intent.DISABLE_SCHEDULE.equals(intent)) return handleToggleSchedule(normalized, userId, false, language);
        if (Intent.ENABLE_SCHEDULE.equals(intent)) return handleToggleSchedule(normalized, userId, true, language);
        if (Intent.DELETE_SCHEDULE.equals(intent)) return handleDeleteSchedule(normalized, userId, language);

        // CREATE_SCHEDULE flow
        return handleCreateSchedule(request, parsed, gemini);
    }

    private AiAssistantResponse handleCreateSchedule(AiAssistantRequest request, ParsedMessage parsed, GeminiParseResult gemini) {
        String conversationId = request.getConversationId();
        String language = request.getLanguage();
        Long userId = request.getUserId();
        
        PendingScheduleAction pending = stateService.computeSchedulePlanningStateIfAbsent(conversationId, k -> {
            PendingScheduleAction p = new PendingScheduleAction();
            p.initialMessage = parsed.getOriginalText();
            return p;
        });

        if (gemini != null && gemini.entries != null && !gemini.entries.isEmpty()) {
            var e = gemini.entries.get(0);
            if (e.amount != null) pending.amount = e.amount;
            if (e.categoryName != null) pending.categoryName = e.categoryName;
            if (e.repeatType != null) pending.repeatType = e.repeatType.toUpperCase();
            if (e.repeatConfig != null) pending.repeatConfig = e.repeatConfig;
            
            // Note logic: Avoid overwriting substantial info with slot-filling values
            if (e.note != null && !e.note.isBlank() && !e.note.equalsIgnoreCase(parsed.getOriginalText())) {
                 pending.note = e.note;
            } else if (pending.note == null && e.note != null) {
                 pending.note = e.note;
            }
        } else {
            BigDecimal amt = parsed.getFirstAmount();
            if (amt != null) pending.amount = amt;
            String cat = entityExtractor.inferCategory(parsed.getNormalizedText());
            if (cat != null) pending.categoryName = cat;
            String repeat = entityExtractor.inferRepeatType(parsed.getNormalizedText());
            if (repeat != null) {
                pending.repeatType = repeat;
                String config = entityExtractor.inferRepeatConfig(parsed.getNormalizedText(), repeat);
                if (config != null) pending.repeatConfig = config;
            }
        }

        // Handle direct repeat info even if gemini is null
        if (pending.repeatType != null) {
            String config = entityExtractor.inferRepeatConfig(parsed.getNormalizedText(), pending.repeatType);
            if (config != null) pending.repeatConfig = config;
        }

        // Slot filling
        if (pending.amount == null) {
            pending.awaitingField = "AMOUNT";
            return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Số tiền định kỳ là bao nhiêu?", "How much is the recurring amount?")).build();
        }
        if (pending.categoryName == null) {
            pending.awaitingField = "CATEGORY";
            return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Cho danh mục nào?", "For which category?") + getCategoryListResponse(com.example.finance_backend.entity.EntryType.EXPENSE, language)).build();
        }
        if (pending.repeatType == null || "NONE".equals(pending.repeatType)) {
            pending.awaitingField = "REPEAT";
            return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Lặp lại thế nào? (Hàng ngày, hàng tuần, hàng tháng)", "What is the repetition? (Daily, Weekly, Monthly)")).build();
        }

        // Additional config for Monthly/Weekly
        if (pending.repeatConfig == null) {
            if ("MONTHLY".equalsIgnoreCase(pending.repeatType)) {
                pending.awaitingField = "REPEAT_CONFIG";
                return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Bạn muốn đặt vào ngày mấy hàng tháng?", "On which day of the month do you want to set it?")).build();
            } else if ("WEEKLY".equalsIgnoreCase(pending.repeatType)) {
                pending.awaitingField = "REPEAT_CONFIG";
                return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(responseGenerator.t(language, "Bạn muốn đặt vào thứ mấy hàng tuần?", "On which day of the week do you want to set it?")).build();
            }
        }

        // Account resolution
        AccountResolution acctRes = resolveAccount(parsed.getOriginalText(), request.getAccountId(), userId, language);
        if (acctRes.needsSelection) {
            pending.awaitingField = "ACCOUNT";
            return AiAssistantResponse.builder().intent("NEED_ACCOUNT").needsAccountSelection(true).reply(responseGenerator.needAccountSelection(language)).build();
        }
        if (acctRes.errorMessage != null) return AiAssistantResponse.builder().intent("CREATE_SCHEDULE").reply(acctRes.errorMessage).build();
        
        pending.accountId = acctRes.accountId;

        // Perform save
        Map<String, Long> nameToId = getNameToIdMap();
        Long categoryId = resolveCategoryId(nameToId, pending.categoryName, resolveFallbackCategoryId(nameToId));
        
        RepeatType rt;
        try { rt = RepeatType.valueOf(pending.repeatType); } catch (Exception ex) { rt = RepeatType.MONTHLY; }

        ScheduleDTO dto = ScheduleDTO.builder()
                .userId(userId)
                .categoryId(categoryId)
                .accountId(pending.accountId)
                .amount(pending.amount)
                .repeatType(rt)
                .repeatConfig(pending.repeatConfig)
                .note(pending.note != null ? pending.note : (pending.initialMessage != null ? pending.initialMessage : parsed.getOriginalText()))
                .isActive(true)
                .startDate(LocalDateTime.now())
                .build();
        
        ScheduleDTO saved = scheduleService.createSchedule(dto);
        stateService.removeSchedulePlanningState(conversationId);
        
        String catName = categoryRepository.findById(categoryId).map(c -> c.getName()).orElse("Khác");
        return AiAssistantResponse.builder()
                .intent("CREATE_SCHEDULE").refreshRequired(true)
                .reply(responseGenerator.createScheduleSuccess(catName, pending.amount, pending.repeatType, saved.getNextRun() != null ? saved.getNextRun().toLocalDate() : LocalDate.now(), language))
                .build();
    }

    private AiAssistantResponse handleListUpcoming(Long userId, String language) {
        List<ScheduleDTO> schedules = scheduleService.getUserSchedules(userId);
        if (schedules.isEmpty()) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.t(language, "Bạn không có lịch trình nào sắp tới.", "You have no upcoming schedules.")).build();
        
        StringBuilder sb = new StringBuilder();
        sb.append(responseGenerator.t(language, "Các khoản chi định kỳ sắp tới:\n", "Upcoming recurring transactions:\n"));
        for (var s : schedules) {
            if (!s.getIsActive()) continue;
            String catName = categoryRepository.findById(s.getCategoryId()).map(c -> c.getName()).orElse("Khác");
            String note = (s.getNote() != null && !s.getNote().isBlank()) ? " (" + s.getNote() + ")" : "";
            String typeTranslate = translateRepeatType(s.getRepeatType().name(), language);
            
            sb.append(String.format("- %s • %s%s (%s)\n", responseGenerator.formatVnd(s.getAmount(), language), catName, note, typeTranslate));
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(sb.toString().trim()).build();
    }

    private AiAssistantResponse handleListSchedules(Long userId, String language) {
        List<ScheduleDTO> schedules = scheduleService.getUserSchedules(userId);
        if (schedules.isEmpty()) return AiAssistantResponse.builder().intent("QUERY").reply(responseGenerator.t(language, "Danh sách lịch trình đang trống.", "Schedule list is empty.")).build();
        
        StringBuilder sb = new StringBuilder();
        sb.append(responseGenerator.t(language, "Danh sách lịch trình của bạn:\n", "Your schedules:\n"));
        for (var s : schedules) {
            String catName = categoryRepository.findById(s.getCategoryId()).map(c -> c.getName()).orElse("Khác");
            String note = (s.getNote() != null && !s.getNote().isBlank()) ? " (" + s.getNote() + ")" : "";
            String typeTranslate = translateRepeatType(s.getRepeatType().name(), language);
            String statusTranslate = s.getIsActive() 
                ? responseGenerator.t(language, "🟢 Đang hoạt động", "🟢 Active")
                : responseGenerator.t(language, "🔴 Đã tạm dừng", "🔴 Inactive");

            sb.append(String.format("- %s • %s%s (%s) [%s]\n", responseGenerator.formatVnd(s.getAmount(), language), catName, note, typeTranslate, statusTranslate));
        }
        return AiAssistantResponse.builder().intent("QUERY").reply(sb.toString().trim()).build();
    }

    private String translateRepeatType(String type, String language) {
        if (!"vi".equals(language)) return type.toLowerCase();
        return switch (type.toUpperCase()) {
            case "DAILY" -> "mỗi ngày";
            case "WEEKLY" -> "mỗi tuần";
            case "MONTHLY" -> "mỗi tháng";
            case "YEARLY" -> "mỗi năm";
            default -> "định kỳ";
        };
    }

    private AiAssistantResponse handleExplainSource(String message, String language) {
        // Find by keyword if possible, else generic explanation
        return AiAssistantResponse.builder().intent("ADVICE").reply(responseGenerator.scheduleExplanationReply("Giao dịch tự động", "MONTHLY", language)).build();
    }

    private AiAssistantResponse handleToggleSchedule(String message, Long userId, boolean active, String language) {
        List<ScheduleDTO> schedules = scheduleService.getUserSchedules(userId);
        String normMessage = textPreprocessor.normalizeVietnamese(message);
        
        String[] words = normMessage.split("\\s+");
        for (var s : schedules) {
            String catName = categoryRepository.findById(s.getCategoryId()).map(c -> c.getName().toLowerCase()).orElse("");
            String normCat = textPreprocessor.normalizeVietnamese(catName);
            String normNote = s.getNote() != null ? textPreprocessor.normalizeVietnamese(s.getNote().toLowerCase()) : "";
            
            boolean match = false;
            for (String w : words) {
                if (w.length() < 3) continue; // Skip common short words like 'da', 'di'
                if (normCat.contains(w) || normNote.contains(w)) {
                    match = true;
                    break;
                }
            }
            
            if (match) {
                s.setIsActive(active);
                scheduleService.updateSchedule(s.getId(), s);
                return AiAssistantResponse.builder().intent("UPDATE").refreshRequired(true).reply(active ? responseGenerator.enableScheduleSuccess(language) : responseGenerator.disableScheduleSuccess(language)).build();
            }
        }
        
        AiAssistantResponse listResponse = handleListSchedules(userId, language);
        String msg = responseGenerator.t(language, "Mình không tìm thấy lịch trình nào khớp với yêu cầu của bạn. Đây là danh sách hiện có:\n\n", "I couldn't find a matching schedule. Here is your current list:\n\n") + listResponse.getReply();
        return AiAssistantResponse.builder().intent("QUERY").reply(msg).build();
    }

    private AiAssistantResponse handleDeleteSchedule(String message, Long userId, String language) {
        List<ScheduleDTO> schedules = scheduleService.getUserSchedules(userId);
        String normMessage = textPreprocessor.normalizeVietnamese(message);
        
        String[] words = normMessage.split("\\s+");
        for (var s : schedules) {
            String catName = categoryRepository.findById(s.getCategoryId()).map(c -> c.getName().toLowerCase()).orElse("");
            String normCat = textPreprocessor.normalizeVietnamese(catName);
            String normNote = s.getNote() != null ? textPreprocessor.normalizeVietnamese(s.getNote().toLowerCase()) : "";
            
            boolean match = false;
            for (String w : words) {
                if (w.length() < 3) continue;
                if (normCat.contains(w) || normNote.contains(w)) {
                    match = true;
                    break;
                }
            }
            
            if (match) {
                scheduleService.deleteSchedule(s.getId());
                return AiAssistantResponse.builder().intent("DELETE").refreshRequired(true).reply(responseGenerator.t(language, "Đã xóa lịch trình.", "Schedule deleted.")).build();
            }
        }
        
        AiAssistantResponse listResponse = handleListSchedules(userId, language);
        String msg = responseGenerator.t(language, "Sory, mình không tìm thấy lịch để xóa. Dưới đây là danh sách lịch của bạn:\n\n", "Sorry, I couldn't find the schedule to delete. Here are your schedules:\n\n") + listResponse.getReply();
        return AiAssistantResponse.builder().intent("QUERY").reply(msg).build();
    }
}
