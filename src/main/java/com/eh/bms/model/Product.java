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
 * A product, with a Bangla and an English name.
 * Example: unit = বস্তা, secondaryUnit = কেজি, conversionFactor = 50 (১ বস্তা = ৫০ কেজি).
 * Units come from the user-managed {@link Unit} list.
 */
@Entity
@Table(name = "products",
        // Names are not unique: the same goods come in different pack sizes (same name, other conversion).
        // ProductService rejects only a full duplicate (same name, units and conversion).
        uniqueConstraints = @UniqueConstraint(name = "uk_products_sku", columnNames = "sku"))
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_products_category"))
    private ProductCategory category;

    /** Bangla name (column kept as "name" from the first version). */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** English name. Nullable only so the column can be added to existing rows; the form requires it. */
    @Column(name = "name_en", length = 150)
    private String nameEn;

    /** Optional. Unique when set. */
    @Column(name = "sku", length = 50)
    private String sku;

    /**
     * Required. Nullable in the database only so rows from the first version (text unit column) can get it
     * from the startup migration; the form requires it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", foreignKey = @ForeignKey(name = "fk_products_unit"))
    private Unit unit;

    /** Optional. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "secondary_unit_id", foreignKey = @ForeignKey(name = "fk_products_secondary_unit"))
    private Unit secondaryUnit;

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

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Unit getUnit() {
        return unit;
    }

    public void setUnit(Unit unit) {
        this.unit = unit;
    }

    public Unit getSecondaryUnit() {
        return secondaryUnit;
    }

    public void setSecondaryUnit(Unit secondaryUnit) {
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
