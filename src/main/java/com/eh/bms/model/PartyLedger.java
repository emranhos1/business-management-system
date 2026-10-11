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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One entry in a party's ledger.
 * balance = previous balance + debit - credit. Positive: party owes us. Negative: we owe the party.
 */
@Entity
@Table(name = "party_ledger",
        indexes = @Index(name = "idx_party_ledger_party_date", columnList = "party_id, date"))
public class PartyLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_party_ledger_party"))
    private Party party;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // VARCHAR, not MySQL ENUM
    @Column(name = "type", nullable = false, length = 20)
    private PartyLedgerType type;

    @Column(name = "debit", nullable = false, precision = 14, scale = 2)
    private BigDecimal debit;

    @Column(name = "credit", nullable = false, precision = 14, scale = 2)
    private BigDecimal credit;

    @Column(name = "balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal balance;

    /** Id of the source record (transaction, payment, ...). */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "note", length = 255)
    private String note;

    public Long getId() {
        return id;
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

    public PartyLedgerType getType() {
        return type;
    }

    public void setType(PartyLedgerType type) {
        this.type = type;
    }

    public BigDecimal getDebit() {
        return debit;
    }

    public void setDebit(BigDecimal debit) {
        this.debit = debit;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public void setCredit(BigDecimal credit) {
        this.credit = credit;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
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
