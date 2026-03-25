package com.example.finance_backend.service;

import com.example.finance_backend.dto.*;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.PasswordResetToken;
import com.example.finance_backend.entity.User;
import com.example.finance_backend.entity.VerificationToken;
import com.example.finance_backend.repository.PasswordResetTokenRepository;
import com.example.finance_backend.repository.UserRepository;
import com.example.finance_backend.repository.VerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final PasswordResetTokenRepository tokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final EmailService emailService;

    public LoginResponse login(LoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng");
        }

        if (!user.isEnabled()) {
            try {
                resendVerificationCode(user.getEmail());
            } catch (Exception e) {
                System.err.println("Không thể gửi lại mã xác minh trong quá trình đăng nhập: " + e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa được kích hoạt. Mã xác minh mới đã được gửi vào email của bạn.");
        }

        return buildLoginResponse(user);
    }

    @Transactional
    public LoginResponse register(RegisterRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được đăng ký");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .displayName(req.getDisplayName() != null && !req.getDisplayName().isBlank() ? req.getDisplayName().trim() : null)
                .enabled(false) // Mặc định là false
                .build();
        user = userRepository.save(user);
        createDefaultAccountForUser(user.getId());
        
        // Gửi mã xác nhận
        sendVerificationCode(user);

        return buildLoginResponse(user);
    }

    private void sendVerificationCode(User user) {
        String code = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        
        // Xóa token cũ nếu có
        verificationTokenRepository.deleteByUser(user);
        verificationTokenRepository.flush();

        VerificationToken verificationToken = VerificationToken.builder()
                .token(code)
                .user(user)
                .expiryDate(LocalDateTime.now().plusMinutes(30))
                .build();
        
        verificationTokenRepository.save(verificationToken);

        try {
            emailService.sendVerificationEmail(user.getEmail(), code);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi khi gửi email xác nhận: " + e.getMessage());
        }
    }

    @Transactional
    public void verifyAccount(String email, String code) {
        User user = userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));

        VerificationToken token = verificationTokenRepository.findByToken(code)
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã xác nhận không đúng"));

        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            verificationTokenRepository.delete(token);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã xác nhận đã hết hạn");
        }

        user.setEnabled(true);
        userRepository.save(user);
        verificationTokenRepository.delete(token);
    }

    @Transactional
    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        
        if (user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản đã được kích hoạt");
        }

        sendVerificationCode(user);
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
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu cũ không chính xác");
        }

        if (oldPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu cũ");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public void updateDisplayName(Long userId, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên không được để trống");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setDisplayName(displayName.trim());
        userRepository.save(user);
    }

    public String uploadAvatar(Long userId, org.springframework.web.multipart.MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        try {
            String dir = "uploads/avatars/";
            java.io.File d = new java.io.File(dir);
            if (!d.exists()) d.mkdirs();
            
            String extension = "";
            String originalFileName = file.getOriginalFilename();
            if (originalFileName != null && originalFileName.lastIndexOf(".") > 0) {
                extension = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            
            String filename = "avatar_" + userId + "_" + System.currentTimeMillis() + extension;
            java.nio.file.Path path = java.nio.file.Paths.get(dir + filename);
            java.nio.file.Files.copy(file.getInputStream(), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            String avatarUrl = "/" + dir + filename;
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);
            return avatarUrl;
        } catch (java.io.IOException ex) {
            throw new RuntimeException("Upload avatar failed", ex);
        }
    }

    @Transactional
    public void forgotPassword(String email) {
        User user = userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng với email này"));

        try {
            // Delete existing tokens for this user
            tokenRepository.deleteByUser(user);
            tokenRepository.flush(); // Force delete to happen before insert
        } catch (Exception e) {
            // If deletion fails (e.g. no tokens), we can ignore or log
        }

        String code = UUID.randomUUID().toString().substring(0, 6).toUpperCase(); 
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(code)
                .user(user)
                .expiryDate(LocalDateTime.now().plusMinutes(30))
                .build();

        tokenRepository.save(resetToken);
        
        try {
            emailService.sendResetPasswordEmail(user.getEmail(), code);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi khi gửi email: " + e.getMessage());
        }
    }

    @Transactional
    public void resetPassword(String code, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code không đúng, vui lòng kiểm tra lại"));

        if (resetToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            tokenRepository.delete(resetToken);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã xác thực đã hết hạn");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        tokenRepository.delete(resetToken);
    }

    @Transactional
    public LoginResponse googleLogin(GoogleLoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(email)
                .map(existingUser -> {
                    // Cập nhật displayName nếu cần (theo thông tin mới nhất từ Google)
                    if (req.getDisplayName() != null && !req.getDisplayName().isBlank()) {
                        existingUser.setDisplayName(req.getDisplayName());
                    }
                    // Đảm bảo user này đã được kích hoạt vì đã verify qua Google
                    existingUser.setEnabled(true);
                    return userRepository.save(existingUser);
                })
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email(email)
                            .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                            .displayName(req.getDisplayName())
                            .enabled(true)
                            .build();
                    newUser = userRepository.save(newUser);
                    createDefaultAccountForUser(newUser.getId());
                    return newUser;
                });

        return buildLoginResponse(user);
    }
}
