package com.example.finance_backend.service;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.User;
import com.example.finance_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String GOOGLE_TOKENINFO = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;
    private final RestTemplate restTemplate = new RestTemplate();

    public LoginResponse login(LoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"));

        if (user.getPasswordHash() == null || user.getPasswordHash().startsWith("GOOGLE_")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản này đăng nhập bằng Google. Vui lòng dùng nút Đăng nhập bằng Google.");
        }
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

    @SuppressWarnings("unchecked")
    public LoginResponse loginWithGoogle(GoogleLoginRequest req) {
        try {
            ResponseEntity<Map> res = restTemplate.getForEntity(GOOGLE_TOKENINFO + req.getIdToken(), Map.class);
            if (res.getStatusCode().isError() || res.getBody() == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token Google không hợp lệ");
            }
            Map<String, Object> claims = res.getBody();
            String email = (String) claims.get("email");
            String name = (String) claims.get("name");
            if (email == null || email.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không lấy được email từ Google");
            }
            final String finalEmail = email.trim().toLowerCase();
            final String finalName = name;
            User user = userRepository.findByEmailIgnoreCase(finalEmail).orElseGet(() -> {
                User newUser = User.builder()
                        .email(finalEmail)
                        .passwordHash("GOOGLE_" + UUID.randomUUID())
                        .displayName(finalName != null && !finalName.isBlank() ? finalName.trim() : finalEmail)
                        .build();
                newUser = userRepository.save(newUser);
                createDefaultAccountForUser(newUser.getId());
                return newUser;
            });
            return buildLoginResponse(user);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Đăng nhập Google thất bại: " + e.getMessage());
        }
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
