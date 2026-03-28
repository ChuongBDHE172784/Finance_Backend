package com.example.finance_backend.dto;

import com.example.finance_backend.entity.RepeatType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleDTO {
    private Long id;
    private Long accountId; // Corresponds to wallet_id
    private Long categoryId;
    private Long userId;
    private BigDecimal amount;
    private String note;
    private LocalDateTime startDate;
    private RepeatType repeatType;
    private String repeatConfig; // JSON mapping
    private com.example.finance_backend.entity.EntryType type;
    private LocalDateTime nextRun;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
