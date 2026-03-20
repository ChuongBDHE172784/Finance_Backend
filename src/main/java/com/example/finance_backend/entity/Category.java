package com.example.finance_backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EntryType type;

    @Column(name = "icon_name", length = 50)
    private String iconName;

    @Column(name = "color_hex", length = 20)
    private String colorHex;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
