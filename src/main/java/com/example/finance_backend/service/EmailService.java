package com.example.finance_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.from-email}")
    private String fromEmail;

    public void sendResetPasswordEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(to);
        message.setSubject("Yêu cầu đặt lại mật khẩu - Finance App");
        message.setText("Chào bạn,\n\n" +
                "Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.\n" +
                "Mã xác nhận (Code) của bạn là: " + code + "\n\n" +
                "Lưu ý: Mã này có hiệu lực trong vòng 30 phút.\n\n" +
                "Nếu bạn không yêu cầu điều này, vui lòng bỏ qua email này.\n\n" +
                "Trân trọng,\n" +
                "Finance App Team");
        mailSender.send(message);
    }
}
