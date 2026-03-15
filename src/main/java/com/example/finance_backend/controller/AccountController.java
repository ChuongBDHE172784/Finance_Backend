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
    public ResponseEntity<List<Account>> getAll() {
        return ResponseEntity.ok(accountService.findAll());
    }

    @PostMapping
    public ResponseEntity<Account> create(@RequestBody Account account) {
        return ResponseEntity.ok(accountService.create(account));
    }
}
