package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Money paid to an employee for one salary month (14.4): an advance (paid before the month's salary is worked
 * out) or part of it. Its account ledger entry has source SALARY and points back here. An emergency advance may
 * go above the month's due; the extra counts as paid for the following months.
 */
@Entity
@Table(name = "salary_payments",
        indexes = {
                @Index(name = "idx_salary_payments_employee_month", columnList = "employee_id, year, month"),
                @Index(name = "idx_salary_payments_year_month", columnList = "year, month")
        })
public class SalaryPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_salary_payments_employee"))
    private Employee employee;

    /** Salary year the payment is for. */
    @Column(name = "year", nullable = false)
    private Integer year;

    /** Salary month the payment is for, 1-12. */
    @Column(name = "month", nullable = false)
    private Integer month;

    /** Date the money was paid. */
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_salary_payments_account"))
    private Account account;

    @Column(name = "note", length = 255)
    private String note;

    /** Emergency advance: allowed above the month's due (rows saved before this column are false). */
    @ColumnDefault("false")
    @Column(name = "emergency", nullable = false)
    private boolean emergency;

    /** Empty for entries made by the system. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_salary_payments_created_by"))
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean isEmergency() {
        return emergency;
    }

    public void setEmergency(boolean emergency) {
        this.emergency = emergency;
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
}
