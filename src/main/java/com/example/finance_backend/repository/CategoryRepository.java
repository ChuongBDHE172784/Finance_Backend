package com.example.finance_backend.repository;

import com.example.finance_backend.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {


    boolean existsByName(String name);

    Optional<Category> findByName(String name);

    Optional<Category> findByNameIgnoreCase(String name);

    List<Category> findByTypeOrderByNameAsc(com.example.finance_backend.entity.EntryType type);
}
