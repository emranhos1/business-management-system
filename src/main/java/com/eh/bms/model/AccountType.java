package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Account type the user manages (e.g. Cash, Bank, bKash).
 * A user-managed lookup table by the user's decision (exception to "types are enums").
 * Cash, Bank, bKash, Nagad and Rocket are seeded on first start.
 */
@Entity
@Table(name = "account_types",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_account_types_name_bn", columnNames = "name_bn"),
                @UniqueConstraint(name = "uk_account_types_name_en", columnNames = "name_en")
        })
public class AccountType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_bn", nullable = false, length = 100)
    private String nameBn;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // VARCHAR, not MySQL ENUM
    @Column(name = "number_rule", nullable = false, length = 20)
    private AccountNumberRule numberRule;

    public Long getId() {
        return id;
    }

    public String getNameBn() {
        return nameBn;
    }

    public void setNameBn(String nameBn) {
        this.nameBn = nameBn;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public AccountNumberRule getNumberRule() {
        return numberRule;
    }

    public void setNumberRule(AccountNumberRule numberRule) {
        this.numberRule = numberRule;
    }
}
