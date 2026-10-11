package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A product. Example: unit = বস্তা, secondaryUnit = kg, conversionFactor = 50 (১ বস্তা = ৫০ kg).
 */
@Entity
@Table(name = "products",
        uniqueConstraints = @UniqueConstraint(name = "uk_products_sku", columnNames = "sku"))
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_products_category"))
    private ProductCategory category;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** Optional. Unique when set. */
    @Column(name = "sku", length = 50)
    private String sku;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit;

    @Column(name = "secondary_unit", length = 30)
    private String secondaryUnit;

    /** How many secondary units make one unit. */
    @Column(name = "conversion_factor", precision = 14, scale = 3)
    private BigDecimal conversionFactor;

    /** Low stock threshold. Null means use app_config low_stock_default. */
    @Column(name = "min_stock_alert", precision = 14, scale = 3)
    private BigDecimal minStockAlert;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public void setCategory(ProductCategory category) {
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getSecondaryUnit() {
        return secondaryUnit;
    }

    public void setSecondaryUnit(String secondaryUnit) {
        this.secondaryUnit = secondaryUnit;
    }

    public BigDecimal getConversionFactor() {
        return conversionFactor;
    }

    public void setConversionFactor(BigDecimal conversionFactor) {
        this.conversionFactor = conversionFactor;
    }

    public BigDecimal getMinStockAlert() {
        return minStockAlert;
    }

    public void setMinStockAlert(BigDecimal minStockAlert) {
        this.minStockAlert = minStockAlert;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
