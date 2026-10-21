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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * One user's salary for one month (14.3, N12). net_salary = gross_salary - deductions + bonus; a deduction has its
 * reason in {@code note}. Payments are in {@code salary_payments}.
 */
@Entity
@Table(name = "salary_processing",
        uniqueConstraints = @UniqueConstraint(name = "uk_salary_processing_user_month",
                columnNames = {"user_id", "year", "month"}),
        indexes = @Index(name = "idx_salary_processing_year_month", columnList = "year, month"))
public class SalaryProcessing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_salary_processing_user"))
    private User user;

    /** 1-12. */
    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    /** Days (daily) or hours (hourly) worked, typed in; the gross of a daily/hourly user is this x rate. */
    @Column(name = "worked_units", precision = 8, scale = 2)
    private BigDecimal workedUnits;

    @Column(name = "gross_salary", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossSalary;

    @Column(name = "deductions", nullable = false, precision = 14, scale = 2)
    private BigDecimal deductions;

    @Column(name = "bonus", nullable = false, precision = 14, scale = 2)
    private BigDecimal bonus;

    @Column(name = "net_salary", nullable = false, precision = 14, scale = 2)
    private BigDecimal netSalary;

    /** The reason of a deduction (required then), or any note. */
    @Column(name = "note", length = 255)
    private String note;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public BigDecimal getWorkedUnits() {
        return workedUnits;
    }

    public void setWorkedUnits(BigDecimal workedUnits) {
        this.workedUnits = workedUnits;
    }

    public BigDecimal getGrossSalary() {
        return grossSalary;
    }

    public void setGrossSalary(BigDecimal grossSalary) {
        this.grossSalary = grossSalary;
    }

    public BigDecimal getDeductions() {
        return deductions;
    }

    public void setDeductions(BigDecimal deductions) {
        this.deductions = deductions;
    }

    public BigDecimal getBonus() {
        return bonus;
    }

    public void setBonus(BigDecimal bonus) {
        this.bonus = bonus;
    }

    public BigDecimal getNetSalary() {
        return netSalary;
    }

    public void setNetSalary(BigDecimal netSalary) {
        this.netSalary = netSalary;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
