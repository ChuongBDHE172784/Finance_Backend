package com.example.finance_backend.service;

import com.example.finance_backend.entity.Account;
import com.example.finance_backend.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<Account> findAll() {
        return accountRepository.findAll();
    }

    @Transactional
    public Account create(Account account) {
        return accountRepository.save(account);
    }

    @Transactional
    public Account updateBalance(Long id, java.math.BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        account.setBalance(account.getBalance().add(amount));
        return accountRepository.save(account);
    }
}
