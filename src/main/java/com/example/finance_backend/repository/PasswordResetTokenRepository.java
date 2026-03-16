package com.example.finance_backend.repository;

import com.example.finance_backend.entity.PasswordResetToken;
import com.example.finance_backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);

    @Modifying
    @Transactional
    void deleteByUser(User user);
}
