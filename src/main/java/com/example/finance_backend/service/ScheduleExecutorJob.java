package com.example.finance_backend.service;

import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.Category;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.entity.RepeatType;
import com.example.finance_backend.entity.Schedule;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.repository.ScheduleRepository;
import com.example.finance_backend.service.impl.ScheduleServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ScheduleExecutorJob {

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private FinancialEntryRepository financialEntryRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private com.example.finance_backend.repository.AccountRepository accountRepository;

    // Run every 10 seconds (for testing, original was 1 hour)
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void executeSchedules() {
        LocalDateTime now = LocalDateTime.now();
        List<Schedule> dueSchedules = scheduleRepository.findByNextRunLessThanEqualAndIsActiveTrue(now);

        log.info("Found {} schedules ready to execute", dueSchedules.size());

        for (Schedule schedule : dueSchedules) {
            try {
                // Fetch category to get EntryType
                Category category = categoryRepository.findById(schedule.getCategoryId()).orElse(null);
                if (category == null) {
                    log.warn("Skipping schedule ID: {} because category ID: {} is not found", 
                        schedule.getId(), schedule.getCategoryId());
                    schedule.setIsActive(false);
                    scheduleRepository.save(schedule);
                    continue;
                }

                // Fetch and check account status
                var accountOpt = accountRepository.findById(schedule.getAccountId());
                if (accountOpt.isEmpty() || accountOpt.get().isDeleted()) {
                    log.warn("Skipping schedule ID: {} because account ID: {} is deleted or not found", 
                        schedule.getId(), schedule.getAccountId());
                    schedule.setIsActive(false);
                    scheduleRepository.save(schedule);
                    continue;
                }

                // Create Transaction
                FinancialEntry entry = FinancialEntry.builder()
                        .type(category != null ? category.getType() : null)
                        .amount(schedule.getAmount())
                        .note(schedule.getNote())
                        .categoryId(schedule.getCategoryId())
                        .accountId(schedule.getAccountId())
                        .userId(schedule.getUserId())
                        .transactionDate(LocalDate.now())
                        .createdByScheduleId(schedule.getId())
                        .build();

                financialEntryRepository.save(entry);

                // Update account balance
                Account account = accountOpt.get();
                if (category.getType() == com.example.finance_backend.entity.EntryType.INCOME) {
                    account.setBalance(account.getBalance().add(schedule.getAmount()));
                } else if (category.getType() == com.example.finance_backend.entity.EntryType.EXPENSE) {
                    account.setBalance(account.getBalance().subtract(schedule.getAmount()));
                }
                accountRepository.save(account);

                // Update Schedule
                if (schedule.getRepeatType() == RepeatType.NONE) {
                    schedule.setIsActive(false);
                } else {
                    LocalDateTime nextRun = ScheduleServiceImpl.calculateNextRunDate(now, schedule.getRepeatType(),
                            schedule.getRepeatConfig());
                    schedule.setNextRun(nextRun);
                }

                scheduleRepository.save(schedule);

                log.info("Successfully executed schedule ID: {}", schedule.getId());

            } catch (Exception e) {
                log.error("Failed to execute schedule ID: {}. Error: {}", schedule.getId(), e.getMessage());
            }
        }
    }
}
