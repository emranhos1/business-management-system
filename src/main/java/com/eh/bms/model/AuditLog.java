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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** One data change: who did what, and when. */
@Entity
@Table(name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_log_timestamp", columnList = "timestamp"),
                @Index(name = "idx_audit_log_module_reference", columnList = "module, reference_id")
        })
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Empty for changes made by the system (e.g. startup seeding). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_audit_log_user"))
    private User user;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "module", nullable = false, length = 50)
    private String module;

    /** Id of the changed record. */
    @Column(name = "reference_id")
    private Long referenceId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) // TEXT (up to 64 KB)
    @Column(name = "old_value", length = 65535)
    private String oldValue;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "new_value", length = 65535)
    private String newValue;

    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(Long referenceId) {
        this.referenceId = referenceId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
