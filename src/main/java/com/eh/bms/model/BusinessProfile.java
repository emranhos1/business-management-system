package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Business details shown on screens and reports. One row only.
 */
@Entity
@Table(name = "business_profile")
public class BusinessProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "currency_symbol", nullable = false, length = 10)
    private String currencySymbol;

    @Column(name = "vat_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatPercentage;

    /** Month the financial year starts (1-12). July (7) in Bangladesh. */
    @Column(name = "financial_year_start", nullable = false)
    private Integer financialYearStart;

    @Column(name = "logo_path", length = 500)
    private String logoPath;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public void setCurrencySymbol(String currencySymbol) {
        this.currencySymbol = currencySymbol;
    }

    public BigDecimal getVatPercentage() {
        return vatPercentage;
    }

    public void setVatPercentage(BigDecimal vatPercentage) {
        this.vatPercentage = vatPercentage;
    }

    public Integer getFinancialYearStart() {
        return financialYearStart;
    }

    public void setFinancialYearStart(Integer financialYearStart) {
        this.financialYearStart = financialYearStart;
    }

    public String getLogoPath() {
        return logoPath;
    }

    public void setLogoPath(String logoPath) {
        this.logoPath = logoPath;
    }
}
