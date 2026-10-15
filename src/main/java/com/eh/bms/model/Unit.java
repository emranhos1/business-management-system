package com.eh.bms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A unit products are counted in (পিস / pcs, কেজি / kg, বস্তা / bag, ...), with a Bangla and an English name.
 * The user manages the list; common units are seeded on first start.
 */
@Entity
@Table(name = "units",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_units_name_bn", columnNames = "name_bn"),
                @UniqueConstraint(name = "uk_units_name_en", columnNames = "name_en")
        })
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_bn", nullable = false, length = 30)
    private String nameBn;

    @Column(name = "name_en", nullable = false, length = 30)
    private String nameEn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
