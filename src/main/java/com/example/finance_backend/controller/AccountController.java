package com.example.finance_backend.controller;

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
    public ResponseEntity<List<Account>> getAll(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return ResponseEntity.ok(accountService.findAll(userId));
    }

    @PostMapping
    public ResponseEntity<Account> create(
            @RequestBody Account account,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return ResponseEntity.ok(accountService.create(account, userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> update(
            @PathVariable Long id,
            @RequestBody Account account,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        account.setId(id);
        return ResponseEntity.ok(accountService.update(account, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            accountService.deleteById(id, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(e.getMessage());
        }
    }
}
