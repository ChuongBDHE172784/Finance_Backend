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
    
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE Schedule s SET s.categoryId = :newCategoryId WHERE s.categoryId = :oldCategoryId")
    void updateCategoryId(@org.springframework.data.repository.query.Param("oldCategoryId") Long oldCategoryId, @org.springframework.data.repository.query.Param("newCategoryId") Long newCategoryId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE Schedule s SET s.isActive = false WHERE s.accountId = :accountId")
    void disableSchedulesByAccountId(@org.springframework.data.repository.query.Param("accountId") Long accountId);
}
