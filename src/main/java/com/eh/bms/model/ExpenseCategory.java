package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * An expense category the user manages (13.1): Bangla and English name.
 * The old {@code type} column is left in the table unused (user decision: no category type).
 */
@Entity
@Table(name = "expense_categories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_expense_categories_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_expense_categories_name_en", columnNames = "name_en")
        })
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** English name (doc/CLAUDE.md: master data has a Bangla and an English name). */
    @Column(name = "name_en", length = 100)
    private String nameEn;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }
}
