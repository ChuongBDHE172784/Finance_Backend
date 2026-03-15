package com.example.finance_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCategoryRequest {

    @NotBlank(message = "Tên danh mục không được để trống")
    @Size(max = 100)
    private String name;

    @Size(max = 50)
    private String iconName;

    @Size(max = 20)
    private String colorHex;

    private Integer sortOrder;
}
