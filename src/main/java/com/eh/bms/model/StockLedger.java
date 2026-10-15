package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One stock movement for a product, in the product's main unit. Quantity is signed: + stock in, - stock out.
 * <p>
 * Snapshots: runningBalance and avgCostAfter are the product's stock and weighted average cost right after
 * this entry was posted (posting order). So the newest row (highest id) of a product gives its current
 * stock and average cost; nothing is cached on {@link Product}. Screens work the running balance out by
 * date instead (an entry may be back-dated), see {@code StockLedgerService.page}.
 */
@Entity
@Table(name = "stock_ledger",
        indexes = {
                @Index(name = "idx_stock_ledger_product_date", columnList = "product_id, date"),
                @Index(name = "idx_stock_ledger_type", columnList = "type")
        })
public class StockLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_stock_ledger_product"))
    private Product product;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // VARCHAR, not MySQL ENUM
    @Column(name = "type", nullable = false, length = 20)
    private StockLedgerType type;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** Cost of one main unit. For stock out: the average cost at that time. */
    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    /** Stock after this entry (posting order). */
    @Column(name = "running_balance", nullable = false, precision = 14, scale = 3)
    private BigDecimal runningBalance;

    /** Weighted average cost after this entry (posting order). */
    @Column(name = "avg_cost_after", nullable = false, precision = 14, scale = 2)
    private BigDecimal avgCostAfter;

    /** Set for ADJUSTMENT entries only. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", length = 20)
    private StockAdjustmentReason reason;

    /** Id of the source record (transaction, adjustment, ...). */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "note", length = 255)
    private String note;

    /** Empty for entries made by the system. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_stock_ledger_created_by"))
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public BigDecimal getAvgCostAfter() {
        return avgCostAfter;
    }

    public void setAvgCostAfter(BigDecimal avgCostAfter) {
        this.avgCostAfter = avgCostAfter;
    }

    public StockAdjustmentReason getReason() {
        return reason;
    }

    public void setReason(StockAdjustmentReason reason) {
        this.reason = reason;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public StockLedgerType getType() {
        return type;
    }

    public void setType(StockLedgerType type) {
        this.type = type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getRunningBalance() {
        return runningBalance;
    }

    public void setRunningBalance(BigDecimal runningBalance) {
        this.runningBalance = runningBalance;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(Long referenceId) {
        this.referenceId = referenceId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
