package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** A product category, with a Bangla and an English name. */
@Entity
@Table(name = "product_categories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_product_categories_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_product_categories_name_en", columnNames = "name_en")
        })
public class ProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bangla name (column kept as "name" from the first version). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** English name. Nullable only so the column can be added to existing rows; the form requires it. */
    @Column(name = "name_en", length = 100)
    private String nameEn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
