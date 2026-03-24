package com.example.finance_backend.repository;

import com.example.finance_backend.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdAndIsDeletedFalseOrderByNameAsc(Long userId);

    @Query("SELECT a FROM Account a WHERE a.userId = :userId AND a.isDeleted = false ORDER BY a.name ASC")
    List<Account> findAllActiveByUserId(Long userId);
}
