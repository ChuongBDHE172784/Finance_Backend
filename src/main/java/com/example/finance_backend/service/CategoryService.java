package com.example.finance_backend.service;

import com.example.finance_backend.dto.CategoryDto;
import com.example.finance_backend.dto.CreateCategoryRequest;
import com.example.finance_backend.entity.Category;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.repository.CategoryRepository;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.service.ai.ResponseGenerator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final FinancialEntryRepository financialEntryRepository;
    private final ResponseGenerator responseGenerator;
    
    @Autowired
    private HttpServletRequest request;

    private String getLang() {
        String lang = request.getHeader("Accept-Language");
        return lang != null ? lang : "vi";
    }

    public List<CategoryDto> findAll() {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(CategoryDto::fromEntity)
                .collect(Collectors.toList());
    }

    public Optional<CategoryDto> findById(Long id) {
        return categoryRepository.findById(id).map(CategoryDto::fromEntity);
    }

    private Map<Long, Category> idToCategoryMapCache;

    public synchronized Map<Long, Category> getIdToCategoryMap() {
        if (idToCategoryMapCache == null) {
            idToCategoryMapCache = categoryRepository.findAll().stream()
                    .collect(Collectors.toMap(Category::getId, c -> c));
        }
        return idToCategoryMapCache;
    }

    public synchronized Map<Long, String> getIdToNameMap() {
        return getIdToCategoryMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName()));
    }

    private synchronized void invalidateCache() {
        idToCategoryMapCache = null;
    }

    @Transactional
    public CategoryDto create(CreateCategoryRequest req) {
        String lang = getLang();
        if ("Khác".equalsIgnoreCase(req.getName().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                responseGenerator.t(lang, "Danh mục 'Khác' đã tồn tại.", "The default 'Other' category already exists.", "既定の「その他」カテゴリは既に存在します。", "기본 '기타' 카테고리가 이미 존재합니다.", "默认的 “其他” 类别已存在。"));
        }
        
        String trimmedName = req.getName().trim();
        if (categoryRepository.findByNameIgnoreCase(trimmedName).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                responseGenerator.t(lang, "Danh mục với tên '" + trimmedName + "' đã tồn tại.", "Category name '" + trimmedName + "' already exists.", "「" + trimmedName + "」という名前のカテゴリは既に存在します。", "이름이 '" + trimmedName + "'인 카테고리가 이미 존재합니다.", "名称为 “" + trimmedName + "” 的类别已存在。"));
        }
        
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
        
        String lang = getLang();
        if ("Khác".equalsIgnoreCase(c.getName()) || "Khác (Thu nhập)".equalsIgnoreCase(c.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                responseGenerator.t(lang, "Không thể chỉnh sửa danh mục mặc định.", "Cannot edit the default category.", "既定のカテゴリを編集することはできません。", "기본 카테고리를 수정할 수 없습니다.", "无法编辑默认类别。"));
        }

        if ("Khác".equalsIgnoreCase(req.getName().trim()) && !c.getName().equalsIgnoreCase("Khác")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                responseGenerator.t(lang, "Không thể đổi tên một danh mục thành 'Khác'.", "Cannot rename a category to 'Other'.", "カテゴリの名前を「その他」に変更することはできません。", "카테고리 이름을 '기타'로 변경할 수 없습니다.", "无法将类别重命名为 “其他”。"));
        }

        String newName = req.getName().trim();
        Optional<Category> existing = categoryRepository.findByNameIgnoreCase(newName);
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                responseGenerator.t(lang, "Danh mục với tên '" + newName + "' đã tồn tại.", "Category name '" + newName + "' already exists.", "「" + newName + "」という名前のカテゴリは既に存在します。", "이름이 '" + newName + "'인 카테고리가 이미 존재합니다.", "名称为 “" + newName + "” 的类别已存在。"));
        }

        c.setName(newName);
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
        Category categoryToDelete = categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));

        if ("Khác".equalsIgnoreCase(categoryToDelete.getName()) || "Khác (Thu nhập)".equalsIgnoreCase(categoryToDelete.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                responseGenerator.t(getLang(), "Không thể xóa danh mục mặc định.", "Cannot delete the default category.", "既定のカテゴリを削除することはできません。", "기본 카테고리를 삭제할 수 없습니다.", "无法删除默认类别。"));
        }

        EntryType deletedType = categoryToDelete.getType();
        String fallbackName = deletedType == EntryType.INCOME ? "Khác (Thu nhập)" : "Khác";
        
        Category fallbackCategory = categoryRepository.findByNameIgnoreCase(fallbackName)
                .orElseGet(() -> categoryRepository.save(Category.builder()
                        .name(fallbackName)
                        .type(deletedType)
                        .iconName(deletedType == EntryType.INCOME ? "attach_money" : "category")
                        .colorHex(deletedType == EntryType.INCOME ? "4CAF50" : "009688")
                        .sortOrder(99)
                        .build()));

        financialEntryRepository.updateCategoryId(id, fallbackCategory.getId());

        categoryRepository.deleteById(id);
        invalidateCache();
    }
}
