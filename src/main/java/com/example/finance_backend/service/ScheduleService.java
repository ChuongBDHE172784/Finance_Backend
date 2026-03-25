package com.example.finance_backend.service;

import com.example.finance_backend.dto.ScheduleDTO;
import java.util.List;

public interface ScheduleService {
    
    ScheduleDTO createSchedule(ScheduleDTO scheduleDTO);
    
    ScheduleDTO updateSchedule(Long id, ScheduleDTO scheduleDTO);
    
    void disableSchedule(Long id);
    
    void enableSchedule(Long id);
    
    void deleteSchedule(Long id);
    
    List<ScheduleDTO> getUserSchedules(Long userId);
}
