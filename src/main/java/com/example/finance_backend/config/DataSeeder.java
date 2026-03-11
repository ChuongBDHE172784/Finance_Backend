package com.example.finance_backend.config;

import com.example.finance_backend.entity.Category;
import com.example.finance_backend.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final CategoryRepository categoryRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (categoryRepository.count() > 0) return;

        List<Category> defaultCategories = List.of(
                Category.builder().name("Ăn uống").iconName("restaurant").colorHex("FF9800").sortOrder(1).build(),
                Category.builder().name("Xăng xe").iconName("local_gas_station").colorHex("2196F3").sortOrder(2).build(),
                Category.builder().name("Mua sắm").iconName("shopping_bag").colorHex("9C27B0").sortOrder(3).build(),
                Category.builder().name("Giải trí").iconName("confirmation_number").colorHex("E91E63").sortOrder(4).build(),
                Category.builder().name("Y tế").iconName("medical_services").colorHex("F44336").sortOrder(5).build(),
                Category.builder().name("Giáo dục").iconName("school").colorHex("3F51B5").sortOrder(6).build(),
                Category.builder().name("Gửi xe").iconName("local_parking").colorHex("795548").sortOrder(7).build(),
                Category.builder().name("Khác").iconName("category").colorHex("009688").sortOrder(99).build()
        );
        categoryRepository.saveAll(defaultCategories);
    }
}
