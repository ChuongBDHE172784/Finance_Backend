package com.example.finance_backend.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class EmailService {

    @Value("${app.sendgrid.api-key}")
    private String sendGridApiKey;

    @Value("${app.from-email}")
    private String fromEmail;

    public void sendResetPasswordEmail(String to, String code) {
        String subject = "Yêu cầu đặt lại mật khẩu - Finance App";
        String body = "Chào bạn,\n\n" +
                "Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.\n" +
                "Mã xác nhận (Code) của bạn là: " + code + "\n\n" +
                "Lưu ý: Mã này có hiệu lực trong vòng 30 phút.\n\n" +
                "Nếu bạn không yêu cầu điều này, vui lòng bỏ qua email này.\n\n" +
                "Trân trọng,\n" +
                "Finance App Team";
        sendEmailViaSendGrid(to, subject, body);
    }

    public void sendVerificationEmail(String to, String code) {
        String subject = "Xác nhận tài khoản - Finance App";
        String body = "Chào bạn,\n\n" +
                "Cảm ơn bạn đã đăng ký tài khoản tại Finance App.\n" +
                "Mã xác nhận (Code) của bạn là: " + code + "\n\n" +
                "Lưu ý: Mã này có hiệu lực trong vòng 30 phút.\n\n" +
                "Vui lòng nhập mã này vào ứng dụng để kích hoạt tài khoản của bạn.\n\n" +
                "Trân trọng,\n" +
                "Finance App Team";
        sendEmailViaSendGrid(to, subject, body);
    }

    private void sendEmailViaSendGrid(String toEmail, String subject, String bodyContent) {
        Email from = new Email(fromEmail);
        Email to = new Email(toEmail);
        Content content = new Content("text/plain", bodyContent);
        Mail mail = new Mail(from, subject, to, content);

        SendGrid sg = new SendGrid(sendGridApiKey);
        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);
            System.out.println("SendGrid Status: " + response.getStatusCode());
            System.out.println("SendGrid Body: " + response.getBody());
            
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                String errorMsg = String.format("SendGrid gửi mail thất bại. Status: %d, Body: %s", 
                        response.getStatusCode(), response.getBody());
                System.err.println(errorMsg);
                throw new RuntimeException(errorMsg);
            }
        } catch (IOException ex) {
            String errorMsg = "Lỗi kết nối SendGrid: " + ex.getMessage();
            System.err.println(errorMsg);
            throw new RuntimeException(errorMsg, ex);
        }
    }
}
