package com.example.finance_backend.controller;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/google-login")
    public ResponseEntity<LoginResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(authService.googleLogin(request));
    }

    @PutMapping("/password")
    public ResponseEntity<String> updatePassword(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody UpdatePasswordRequest request) {
        authService.updatePassword(userId, request.getOldPassword(), request.getNewPassword());
        return ResponseEntity.ok("Đổi mật khẩu thành công");
    }

    @PostMapping("/avatar")
    public ResponseEntity<String> uploadAvatar(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        String avatarUrl = authService.uploadAvatar(userId, file);
        return ResponseEntity.ok(avatarUrl);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestParam String email) {
        authService.forgotPassword(email);
        return ResponseEntity.ok("Mã xác thực đã được gửi về email của bạn");
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getCode(), request.getNewPassword());
        return ResponseEntity.ok("Đặt lại mật khẩu thành công");
    }

    @PostMapping("/verify-account")
    public ResponseEntity<String> verifyAccount(@RequestParam String email, @RequestParam String code) {
        authService.verifyAccount(email, code);
        return ResponseEntity.ok("Kích hoạt tài khoản thành công. Bây giờ bạn có thể đăng nhập.");
    }

    @PostMapping("/resend-verification-code")
    public ResponseEntity<String> resendVerificationCode(@RequestParam String email) {
        authService.resendVerificationCode(email);
        return ResponseEntity.ok("Mã xác minh mới đã được gửi vào email của bạn.");
    }
}
