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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A sale, purchase, expense, salary or transfer ({@code transactions} table).
 * Named "BusinessTransaction" to avoid a clash with Hibernate's Transaction.
 */
@Entity
@Table(name = "transactions",
        indexes = @Index(name = "idx_transactions_type_date", columnList = "type, date"),
        // Memo numbers never repeat within a type (sale 1, 2, 3 ...; purchase has its own); also speeds search by memo no.
        uniqueConstraints = @UniqueConstraint(name = "uk_transactions_type_memo", columnNames = {"type", "memo_no"}))
public class BusinessTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // VARCHAR, not MySQL ENUM
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    /** Null for transactions without a party (expense, salary, transfer). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", foreignKey = @ForeignKey(name = "fk_transactions_party"))
    private Party party;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discount;

    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "due_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal dueAmount;

    /** Main method. Mixed payments are listed in transaction_payments. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "note", length = 255)
    private String note;

    /** Memo (invoice) number shown to the customer: 1, 2, 3 ... per type, never reused (11.8). */
    @Column(name = "memo_no")
    private Long memoNo;

    /** The supplier's own memo (bill) number on a purchase; optional, searchable. */
    @Column(name = "supplier_memo_no", length = 50)
    private String supplierMemoNo;

    /** Value of the goods that came back (sales return, 11.7); null = nothing returned. */
    @Column(name = "returned_amount", precision = 14, scale = 2)
    private BigDecimal returnedAmount;

    public Long getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public Party getParty() {
        return party;
    }

    public void setParty(Party party) {
        this.party = party;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(BigDecimal grandTotal) {
        this.grandTotal = grandTotal;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigDecimal getDueAmount() {
        return dueAmount;
    }

    public void setDueAmount(BigDecimal dueAmount) {
        this.dueAmount = dueAmount;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getMemoNo() {
        return memoNo;
    }

    public void setMemoNo(Long memoNo) {
        this.memoNo = memoNo;
    }

    public String getSupplierMemoNo() {
        return supplierMemoNo;
    }

    public void setSupplierMemoNo(String supplierMemoNo) {
        this.supplierMemoNo = supplierMemoNo;
    }

    /** Never null: 0 when nothing came back. */
    public BigDecimal getReturnedAmount() {
        return returnedAmount == null ? BigDecimal.ZERO : returnedAmount;
    }

    public void setReturnedAmount(BigDecimal returnedAmount) {
        this.returnedAmount = returnedAmount;
    }
}
