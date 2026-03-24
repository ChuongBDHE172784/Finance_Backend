package com.example.finance_backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAssistantRequest {

    private String message;

    private String conversationId;

    private Long accountId;

    /** Mã ngôn ngữ từ client (VD: "vi", "en"). */
    private String language;

    /** ID người dùng từ header X-User-Id (để giới hạn phạm vi giao dịch/tài khoản). */
    private Long userId;

    /** Ảnh được mã hóa Base64 cho phân tích OCR/Hình ảnh. */
    private String base64Image;
}
