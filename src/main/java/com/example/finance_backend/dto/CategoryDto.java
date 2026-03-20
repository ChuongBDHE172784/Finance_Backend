package com.example.finance_backend.dto;

import com.example.finance_backend.entity.Category;
import com.example.finance_backend.entity.EntryType;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryDto {
    private Long id;
    private String name;
    private EntryType type;
    private String iconName;
    private String colorHex;
    private Integer sortOrder;

    public static CategoryDto fromEntity(Category c) {
        return CategoryDto.builder()
                .id(c.getId())
                .name(c.getName())
                .type(c.getType())
                .iconName(c.getIconName())
                .colorHex(c.getColorHex())
                .sortOrder(c.getSortOrder())
                .build();
    }
}
