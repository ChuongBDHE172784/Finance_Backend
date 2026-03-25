package com.example.finance_backend.service;

import com.example.finance_backend.entity.Account;
import com.example.finance_backend.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final com.example.finance_backend.repository.ScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public List<Account> findAll(Long userId, boolean includeDeleted) {
        if (userId == null) return List.of();
        if (includeDeleted) {
            return accountRepository.findByUserIdOrderByIsDeletedAscNameAsc(userId);
        }
        return accountRepository.findByUserIdAndIsDeletedFalseOrderByNameAsc(userId);
    }

    @Transactional
    public Account create(Account account, Long userId) {
        if (userId != null) account.setUserId(userId);
        
        // Kiểm tra xem đã có ví/tài khoản nào cùng tên mà chưa bị xóa không
        if (accountRepository.existsByUserIdAndNameAndIsDeletedFalse(userId, account.getName())) {
            throw new IllegalArgumentException("Đã tồn tại ví/tài khoản với tên này.");
        }
        
        return accountRepository.save(account);
    }

    @Transactional
    public Account update(Account account, Long userId) {
        Account existing = accountRepository.findById(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (userId != null && !userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("Account not found");
        }
        if (existing.isDeleted()) {
            throw new IllegalStateException("Cannot update a deleted account");
        }
        
        // Nếu thay đổi tên, kiểm tra xem tên mới đã tồn tại trong các ví đang hoạt động chưa
        if (!existing.getName().equals(account.getName())) {
            if (accountRepository.existsByUserIdAndNameAndIsDeletedFalse(userId, account.getName())) {
                throw new IllegalArgumentException("Tên ví/tài khoản này đã tồn tại.");
            }
        }
        
        existing.setName(account.getName());
        existing.setBalance(account.getBalance() != null ? account.getBalance() : existing.getBalance());
        if (account.getIconName() != null) existing.setIconName(account.getIconName());
        if (account.getColorHex() != null) existing.setColorHex(account.getColorHex());
        return accountRepository.save(existing);
    }

    /**
     * Xóa ví/tài khoản (Soft delete).
     * Chuyển trạng thái isDeleted = true và cho số dư về 0.
     * Giao dịch vẫn có thể tham chiếu tới ví này trong lịch sử.
     */
    @Transactional
    public void deleteById(Long id, Long userId) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (userId != null && !userId.equals(account.getUserId())) {
            throw new IllegalArgumentException("Account not found");
        }
        
        // Thực hiện soft delete: đánh dấu đã xóa và reset số dư
        accountRepository.softDeleteById(id);
        
        // Tắt các lịch trình liên quan đến ví này
        scheduleRepository.disableSchedulesByAccountId(id);
        
        log.info("Soft-deleted account ID: {} and deactivated its schedules", id);
    }

    @Transactional
    public Account updateBalance(Long id, java.math.BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        account.setBalance(account.getBalance().add(amount));
        return accountRepository.save(account);
    }
}
