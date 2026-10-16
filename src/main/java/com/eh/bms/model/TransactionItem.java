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

import java.math.BigDecimal;

/** One product line of a sale or purchase. */
@Entity
@Table(name = "transaction_items")
public class TransactionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_transaction_items_transaction"))
    private BusinessTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_transaction_items_product"))
    private Product product;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** Name (English) of the unit used on this line, kept as text from the first version; see {@link #unitRef}. */
    @Column(name = "unit", nullable = false, length = 30)
    private String unit;

    /** Unit used on this line: the product's unit or its secondary unit. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", foreignKey = @ForeignKey(name = "fk_transaction_items_unit"))
    private Unit unitRef;

    /**
     * {@link #quantity} in the product's main unit (what stock moved by), saved at sale time so a later change
     * of the product's conversion does not change past sales.
     */
    @Column(name = "main_quantity", precision = 14, scale = 3)
    private BigDecimal mainQuantity;

    /** How much of {@link #quantity} came back (sales return, 11.7), in the line's unit. */
    @Column(name = "returned_quantity", precision = 14, scale = 3)
    private BigDecimal returnedQuantity;

    public Unit getUnitRef() {
        return unitRef;
    }

    public void setUnitRef(Unit unitRef) {
        this.unitRef = unitRef;
    }

    public BigDecimal getMainQuantity() {
        return mainQuantity;
    }

    public void setMainQuantity(BigDecimal mainQuantity) {
        this.mainQuantity = mainQuantity;
    }

    /** Never null: 0 when nothing came back. */
    public BigDecimal getReturnedQuantity() {
        return returnedQuantity == null ? BigDecimal.ZERO : returnedQuantity;
    }

    public void setReturnedQuantity(BigDecimal returnedQuantity) {
        this.returnedQuantity = returnedQuantity;
    }

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "discount_per_unit", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountPerUnit;

    @Column(name = "total_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalPrice;

    public Long getId() {
        return id;
    }

    public BusinessTransaction getTransaction() {
        return transaction;
    }

    public void setTransaction(BusinessTransaction transaction) {
        this.transaction = transaction;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getDiscountPerUnit() {
        return discountPerUnit;
    }

    public void setDiscountPerUnit(BigDecimal discountPerUnit) {
        this.discountPerUnit = discountPerUnit;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }
}
