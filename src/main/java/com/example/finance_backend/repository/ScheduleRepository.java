package com.example.finance_backend.repository;

import com.example.finance_backend.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    
    List<Schedule> findByUserId(Long userId);
    
    List<Schedule> findByNextRunLessThanEqualAndIsActiveTrue(LocalDateTime time);
}
