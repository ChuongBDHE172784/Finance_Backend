package com.example.finance_backend.service;

import com.example.finance_backend.dto.CategoryDto;
import com.example.finance_backend.dto.CreateCategoryRequest;
import com.example.finance_backend.entity.Category;
import com.example.finance_backend.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<CategoryDto> findAll() {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(CategoryDto::fromEntity)
                .collect(Collectors.toList());
    }

    public Optional<CategoryDto> findById(Long id) {
        return categoryRepository.findById(id).map(CategoryDto::fromEntity);
    }

    private Map<Long, String> idToNameMapCache;

    public synchronized Map<Long, String> getIdToNameMap() {
        if (idToNameMapCache == null) {
            idToNameMapCache = categoryRepository.findAll().stream()
                    .collect(Collectors.toMap(Category::getId, Category::getName));
        }
        return idToNameMapCache;
    }

    private synchronized void invalidateCache() {
        idToNameMapCache = null;
    }

    @Transactional
    public CategoryDto create(CreateCategoryRequest req) {
        Integer sortOrder = req.getSortOrder();
        if (sortOrder == null) {
            long maxOrder = categoryRepository.findAll().stream()
                    .mapToLong(c -> c.getSortOrder() != null ? c.getSortOrder() : 0)
                    .max()
                    .orElse(0);
            sortOrder = (int) maxOrder + 1;
        }
        Category c = Category.builder()
                .name(req.getName().trim())
                .type(req.getType())
                .iconName(req.getIconName() != null ? req.getIconName().trim() : null)
                .colorHex(req.getColorHex() != null ? req.getColorHex().trim() : null)
                .sortOrder(sortOrder)
                .build();
        c = categoryRepository.save(c);
        invalidateCache();
        return CategoryDto.fromEntity(c);
    }

    @Transactional
    public CategoryDto update(Long id, CreateCategoryRequest req) {
        Category c = categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        c.setName(req.getName().trim());
        c.setType(req.getType());
        c.setIconName(req.getIconName() != null ? req.getIconName().trim() : null);
        c.setColorHex(req.getColorHex() != null ? req.getColorHex().trim() : null);
        if (req.getSortOrder() != null) {
            c.setSortOrder(req.getSortOrder());
        }
        c = categoryRepository.save(c);
        invalidateCache();
        return CategoryDto.fromEntity(c);
    }

    @Transactional
    public void deleteById(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
        }
        categoryRepository.deleteById(id);
        invalidateCache();
    }
}
