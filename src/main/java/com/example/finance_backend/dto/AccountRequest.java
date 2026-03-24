package com.example.finance_backend.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AccountRequest {
    private String name;
    private BigDecimal balance;
    private String iconName;
    private String colorHex;
}
