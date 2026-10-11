package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Customer, supplier or both (unified). */
@Entity
@Table(name = "parties",
        uniqueConstraints = @UniqueConstraint(name = "uk_parties_phone", columnNames = "phone"),
        indexes = @Index(name = "idx_parties_type", columnList = "type"))
public class Party {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** Required and unique. */
    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "address", length = 255)
    private String address;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // VARCHAR, not MySQL ENUM
    @Column(name = "type", nullable = false, length = 20)
    private PartyType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "party_type", length = 20)
    private PartyTradeType partyType;

    @Column(name = "opening_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingBalance;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "balance_type", nullable = false, length = 20)
    private BalanceType balanceType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public PartyType getType() {
        return type;
    }

    public void setType(PartyType type) {
        this.type = type;
    }

    public PartyTradeType getPartyType() {
        return partyType;
    }

    public void setPartyType(PartyTradeType partyType) {
        this.partyType = partyType;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    public BalanceType getBalanceType() {
        return balanceType;
    }

    public void setBalanceType(BalanceType balanceType) {
        this.balanceType = balanceType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
