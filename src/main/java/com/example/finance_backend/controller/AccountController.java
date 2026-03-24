package com.example.finance_backend.controller;

import com.example.finance_backend.dto.AccountRequest;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    public ResponseEntity<List<Account>> getAll(@RequestHeader(value = "X-User-Id", required = false) String userIdStr) {
        Long userId = parseUserId(userIdStr);
        return ResponseEntity.ok(accountService.findAll(userId));
    }

    @PostMapping
    public ResponseEntity<Account> create(
            @RequestBody AccountRequest req,
            @RequestHeader(value = "X-User-Id", required = false) String userIdStr) {
        Long userId = parseUserId(userIdStr);
        Account account = Account.builder()
                .name(req.getName())
                .balance(req.getBalance() != null ? req.getBalance() : java.math.BigDecimal.ZERO)
                .iconName(req.getIconName())
                .colorHex(req.getColorHex())
                .build();
        return ResponseEntity.ok(accountService.create(account, userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> update(
            @PathVariable Long id,
            @RequestBody AccountRequest req,
            @RequestHeader(value = "X-User-Id", required = false) String userIdStr) {
        Long userId = parseUserId(userIdStr);
        Account account = Account.builder()
                .id(id)
                .name(req.getName())
                .balance(req.getBalance())
                .iconName(req.getIconName())
                .colorHex(req.getColorHex())
                .build();
        return ResponseEntity.ok(accountService.update(account, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userIdStr) {
        Long userId = parseUserId(userIdStr);
        try {
            accountService.deleteById(id, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    private Long parseUserId(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("undefined")) return null;
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
