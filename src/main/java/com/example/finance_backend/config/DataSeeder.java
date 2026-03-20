package com.example.finance_backend.config;

import com.example.finance_backend.entity.Account;
import com.example.finance_backend.entity.Category;
import com.example.finance_backend.entity.User;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.repository.UserRepository;
import com.example.finance_backend.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaultUser();
        // Chi tiêu (Expense)
        ensureCategory("Ăn uống", "restaurant", "FF9800", 1, com.example.finance_backend.entity.EntryType.EXPENSE);
        ensureCategory("Xăng xe", "local_gas_station", "2196F3", 2, com.example.finance_backend.entity.EntryType.EXPENSE);
        ensureCategory("Mua sắm", "shopping_bag", "9C27B0", 3, com.example.finance_backend.entity.EntryType.EXPENSE);
        ensureCategory("Giải trí", "confirmation_number", "E91E63", 4, com.example.finance_backend.entity.EntryType.EXPENSE);
        ensureCategory("Y tế", "medical_services", "F44336", 5, com.example.finance_backend.entity.EntryType.EXPENSE);
        ensureCategory("Giáo dục", "school", "3F51B5", 6, com.example.finance_backend.entity.EntryType.EXPENSE);
        ensureCategory("Gửi xe", "local_parking", "795548", 7, com.example.finance_backend.entity.EntryType.EXPENSE);
        
        // Thu nhập (Income)
        ensureCategory("Lương", "payments", "4CAF50", 10, com.example.finance_backend.entity.EntryType.INCOME);
        ensureCategory("Nạp tiền", "account_balance_wallet", "4CAF50", 11, com.example.finance_backend.entity.EntryType.INCOME, List.of("Nạp ví"));
        
        ensureCategory("Khác", "category", "009688", 99, com.example.finance_backend.entity.EntryType.EXPENSE);
    }

    private void ensureDefaultUser() {
        if (userRepository.findByEmailIgnoreCase("admin@example.com").isPresent()) return;
        User admin = userRepository.save(User.builder()
                .email("admin@example.com")
                .passwordHash(passwordEncoder.encode("123456"))
                .displayName("Quản trị viên")
                .enabled(true)
                .build());
        Account defaultAccount = Account.builder()
                .name("Ví chính")
                .balance(java.math.BigDecimal.ZERO)
                .build();
        accountService.create(defaultAccount, admin.getId());
    }

    private void ensureCategory(String name, String icon, String color, int order, com.example.finance_backend.entity.EntryType type) {
        ensureCategory(name, icon, color, order, type, List.of());
    }

    private void ensureCategory(String name, String icon, String color, int order, com.example.finance_backend.entity.EntryType type, List<String> aliases) {
        Optional<Category> existing = categoryRepository.findByName(name);
        if (existing.isPresent()) {
            Category c = existing.get();
            if (c.getType() == null) {
                c.setType(type);
                categoryRepository.save(c);
            }
            return;
        }

        for (String alias : aliases) {
            Optional<Category> aliasCategory = categoryRepository.findByName(alias);
            if (aliasCategory.isPresent()) {
                Category c = aliasCategory.get();
                c.setName(name);
                c.setType(type);
                categoryRepository.save(c);
                return;
            }
        }

        categoryRepository.save(Category.builder()
                .name(name)
                .type(type)
                .iconName(icon)
                .colorHex(color)
                .sortOrder(order)
                .build());
    }
}
