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
    private final com.example.finance_backend.repository.FinancialEntryRepository entryRepository;

    @Transactional(readOnly = true)
    public List<Account> findAll(Long userId) {
        if (userId == null) return List.of();
        return accountRepository.findByUserIdOrderByNameAsc(userId);
    }

    @Transactional
    public Account create(Account account, Long userId) {
        if (userId != null) account.setUserId(userId);
        return accountRepository.save(account);
    }

    @Transactional
    public Account update(Account account, Long userId) {
        Account existing = accountRepository.findById(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (userId != null && !userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("Account not found");
        }
        existing.setName(account.getName());
        existing.setBalance(account.getBalance() != null ? account.getBalance() : existing.getBalance());
        if (account.getIconName() != null) existing.setIconName(account.getIconName());
        if (account.getColorHex() != null) existing.setColorHex(account.getColorHex());
        return accountRepository.save(existing);
    }

    /**
     * Xóa ví/tài khoản. Chỉ cho phép khi không còn giao dịch nào tham chiếu tới ví này.
     */
    @Transactional
    public void deleteById(Long id, Long userId) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (userId != null && !userId.equals(account.getUserId())) {
            throw new IllegalArgumentException("Account not found");
        }
        long count = entryRepository.countByAccountId(id);
        if (count > 0) {
            throw new IllegalStateException(
                    "Không thể xóa ví đang có " + count + " giao dịch. Hãy xóa hoặc chuyển giao dịch sang ví khác trước.");
        }
        accountRepository.delete(account);
    }

    @Transactional
    public Account updateBalance(Long id, java.math.BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        account.setBalance(account.getBalance().add(amount));
        return accountRepository.save(account);
    }
}
