package com.example.finance_backend.service;

import com.example.finance_backend.dto.LoginRequest;
import com.example.finance_backend.dto.LoginResponse;
import com.example.finance_backend.dto.RegisterRequest;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.User;
import com.example.finance_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;

    public LoginResponse login(LoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng");
        }

        return buildLoginResponse(user);
    }

    public LoginResponse register(RegisterRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được đăng ký");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .displayName(req.getDisplayName() != null && !req.getDisplayName().isBlank() ? req.getDisplayName().trim() : null)
                .build();
        user = userRepository.save(user);
        createDefaultAccountForUser(user.getId());
        return buildLoginResponse(user);
    }

    private void createDefaultAccountForUser(Long userId) {
        Account defaultAccount = Account.builder()
                .name("Ví chính")
                .balance(BigDecimal.ZERO)
                .build();
        accountService.create(defaultAccount, userId);
    }

    private LoginResponse buildLoginResponse(User user) {
        String token = UUID.randomUUID().toString();
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName() != null ? user.getDisplayName() : user.getEmail())
                .build();
    }
}
