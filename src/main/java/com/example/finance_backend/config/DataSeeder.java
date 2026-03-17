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
        ensureCategory("Ăn uống", "restaurant", "FF9800", 1);
        ensureCategory("Xăng xe", "local_gas_station", "2196F3", 2);
        ensureCategory("Mua sắm", "shopping_bag", "9C27B0", 3);
        ensureCategory("Giải trí", "confirmation_number", "E91E63", 4);
        ensureCategory("Y tế", "medical_services", "F44336", 5);
        ensureCategory("Giáo dục", "school", "3F51B5", 6);
        ensureCategory("Gửi xe", "local_parking", "795548", 7);
        ensureCategory("Nạp tiền", "account_balance_wallet", "4CAF50", 8, List.of("Nạp ví"));
        ensureCategory("Khác", "category", "009688", 99);
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

    private void ensureCategory(String name, String icon, String color, int order) {
        ensureCategory(name, icon, color, order, List.of());
    }

    private void ensureCategory(String name, String icon, String color, int order, List<String> aliases) {
        Optional<Category> existing = categoryRepository.findByName(name);
        if (existing.isPresent()) return;

        for (String alias : aliases) {
            Optional<Category> aliasCategory = categoryRepository.findByName(alias);
            if (aliasCategory.isPresent()) {
                Category c = aliasCategory.get();
                c.setName(name);
                categoryRepository.save(c);
                return;
            }
        }

        categoryRepository.save(Category.builder()
                .name(name)
                .iconName(icon)
                .colorHex(color)
                .sortOrder(order)
                .build());
    }
}
