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
import java.time.LocalDate;

/**
 * One employee's salary sheet for one month (14.3).
 * net_salary = gross_salary - deductions + bonus.
 * Payments are in {@code salary_payments}; account_id and paid_date are no longer used (kept, always empty).
 */
@Entity
@Table(name = "salary_processing",
        uniqueConstraints = @UniqueConstraint(name = "uk_salary_processing_employee_month",
                columnNames = {"employee_id", "year", "month"}),
        indexes = @Index(name = "idx_salary_processing_year_month", columnList = "year, month"))
public class SalaryProcessing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_salary_processing_employee"))
    private Employee employee;

    /** 1-12. */
    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "working_days", nullable = false)
    private Integer workingDays;

    @Column(name = "present_days", nullable = false)
    private Integer presentDays;

    @Column(name = "leave_days", nullable = false)
    private Integer leaveDays;

    /** Days (daily, monthly) or hours (hourly) worked; the gross of a daily/hourly employee is this x rate. */
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

    /** No longer used: payments are in salary_payments. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id",
            foreignKey = @ForeignKey(name = "fk_salary_processing_account"))
    private Account account;

    /** No longer used: payments are in salary_payments. */
    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "note", length = 255)
    private String note;

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
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

    public Integer getWorkingDays() {
        return workingDays;
    }

    public void setWorkingDays(Integer workingDays) {
        this.workingDays = workingDays;
    }

    public Integer getPresentDays() {
        return presentDays;
    }

    public void setPresentDays(Integer presentDays) {
        this.presentDays = presentDays;
    }

    public Integer getLeaveDays() {
        return leaveDays;
    }

    public void setLeaveDays(Integer leaveDays) {
        this.leaveDays = leaveDays;
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

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public LocalDate getPaidDate() {
        return paidDate;
    }

    public void setPaidDate(LocalDate paidDate) {
        this.paidDate = paidDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
