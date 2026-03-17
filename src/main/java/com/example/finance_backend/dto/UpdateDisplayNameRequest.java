package com.example.finance_backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateDisplayNameRequest {
    @NotBlank(message = "Tên không được để trống")
    private String displayName;
}
