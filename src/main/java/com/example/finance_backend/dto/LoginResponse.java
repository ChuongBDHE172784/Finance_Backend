package com.example.finance_backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponse {

    private String token;
    private Long userId;
    private String email;
    private String displayName;
    private String avatarUrl;
}
