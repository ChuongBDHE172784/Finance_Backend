package com.example.finance_backend.controller;

import com.example.finance_backend.entity.Budget;
import com.example.finance_backend.repository.BudgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/budgets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class BudgetController {

    private final BudgetRepository budgetRepository;

    @GetMapping
    public List<Budget> getAllBudgets(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return budgetRepository.findByUserId(userId);
    }

    @PostMapping
    public ResponseEntity<Budget> createOrUpdateBudget(
            @RequestBody Budget budget,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {

        if (userId != null) {
            budget.setUserId(userId);
        }

        if (budget.getUserId() == null) {
            return ResponseEntity.badRequest().build();
        }
        // Basic upsert detection if ID is null
        if (budget.getId() == null) {
            budgetRepository.findFirstByUserIdAndCategoryIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            budget.getUserId(), budget.getCategoryId(), budget.getStartDate(), budget.getEndDate())
                    .ifPresent(ex -> budget.setId(ex.getId()));
        }
        return ResponseEntity.ok(budgetRepository.save(budget));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(@PathVariable Long id) {
        budgetRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
