package com.example.finance_backend.service.impl;

import com.example.finance_backend.dto.ScheduleDTO;
import com.example.finance_backend.entity.RepeatType;
import com.example.finance_backend.entity.Schedule;
import com.example.finance_backend.repository.ScheduleRepository;
import com.example.finance_backend.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduleServiceImpl implements ScheduleService {

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Override
    public ScheduleDTO createSchedule(ScheduleDTO dto) {
        Schedule schedule = mapToEntity(dto);
        schedule.setIsActive(true);
        if (schedule.getNextRun() == null) {
            schedule.setNextRun(calculateNextRunDate(schedule.getStartDate(), schedule.getRepeatType(), schedule.getRepeatConfig()));
        }
        Schedule saved = scheduleRepository.save(schedule);
        return mapToDto(saved);
    }

    @Override
    public ScheduleDTO updateSchedule(Long id, ScheduleDTO dto) {
        Schedule existing = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
        
        existing.setAmount(dto.getAmount());
        existing.setNote(dto.getNote());
        existing.setRepeatType(dto.getRepeatType());
        existing.setRepeatConfig(dto.getRepeatConfig());
        existing.setStartDate(dto.getStartDate());
        existing.setCategoryId(dto.getCategoryId());
        existing.setAccountId(dto.getAccountId());
        
        // Recalculate next run if modifying repeat logic
        if (dto.getNextRun() != null) {
            existing.setNextRun(dto.getNextRun());
        } else {
            existing.setNextRun(calculateNextRunDate(LocalDateTime.now(), existing.getRepeatType(), existing.getRepeatConfig()));
        }

        Schedule saved = scheduleRepository.save(existing);
        return mapToDto(saved);
    }

    @Override
    public void disableSchedule(Long id) {
        Schedule existing = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
        existing.setIsActive(false);
        scheduleRepository.save(existing);
    }

    @Override
    public void enableSchedule(Long id) {
        Schedule existing = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
        existing.setIsActive(true);
        scheduleRepository.save(existing);
    }

    @Override
    public void deleteSchedule(Long id) {
        scheduleRepository.deleteById(id);
    }

    @Override
    public List<ScheduleDTO> getUserSchedules(Long userId) {
        return scheduleRepository.findByUserId(userId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public static LocalDateTime calculateNextRunDate(LocalDateTime fromDate, RepeatType type, String config) {
        switch (type) {
            case DAILY:
                return fromDate.plusDays(1);
            case WEEKLY:
                return fromDate.plusWeeks(1);
            case MONTHLY:
                return fromDate.plusMonths(1);
            case YEARLY:
                return fromDate.plusYears(1);
            case CUSTOM:
                if (config != null && !config.isEmpty()) {
                    try {
                        String[] dateStrings = config.split(",");
                        LocalDateTime earliestNext = null;
                        for (String ds : dateStrings) {
                            LocalDateTime d = LocalDateTime.parse(ds);
                            if (d.isAfter(fromDate)) {
                                if (earliestNext == null || d.isBefore(earliestNext)) {
                                    earliestNext = d;
                                }
                            }
                        }
                        if (earliestNext != null) return earliestNext;
                    } catch (Exception e) {
                        // fallback if not ISO or other error
                    }
                }
                return fromDate.plusDays(1);
            case NONE:
            default:
                return fromDate; // Won't run again automatically after first execution
        }
    }

    private Schedule mapToEntity(ScheduleDTO dto) {
        return Schedule.builder()
                .id(dto.getId())
                .accountId(dto.getAccountId())
                .categoryId(dto.getCategoryId())
                .userId(dto.getUserId())
                .amount(dto.getAmount())
                .note(dto.getNote())
                .startDate(dto.getStartDate())
                .repeatType(dto.getRepeatType())
                .repeatConfig(dto.getRepeatConfig())
                .nextRun(dto.getNextRun())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();
    }

    private ScheduleDTO mapToDto(Schedule schedule) {
        return ScheduleDTO.builder()
                .id(schedule.getId())
                .accountId(schedule.getAccountId())
                .categoryId(schedule.getCategoryId())
                .userId(schedule.getUserId())
                .amount(schedule.getAmount())
                .note(schedule.getNote())
                .startDate(schedule.getStartDate())
                .repeatType(schedule.getRepeatType())
                .repeatConfig(schedule.getRepeatConfig())
                .nextRun(schedule.getNextRun())
                .isActive(schedule.getIsActive())
                .createdAt(schedule.getCreatedAt())
                .updatedAt(schedule.getUpdatedAt())
                .build();
    }
}
