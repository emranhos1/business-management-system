package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.AuditAction;
import com.eh.bms.model.AuditLog;
import com.eh.bms.model.User;
import org.hibernate.Session;

/**
 * Writes {@code audit_log} rows for data changes.
 * Call inside the same transaction as the change, so the audit row commits or rolls back with it:
 * <pre>{@code
 * TransactionHelper.runInTransaction(session -> {
 *     session.persist(party);
 *     auditService.log(session, AuditAction.CREATE, AppModule.PARTY, party.getId(), null, "name=" + party.getName());
 * });
 * }</pre>
 * The user comes from {@link UserSession}; with nobody logged in (system work) it is left empty.
 * When one module has several kinds of rows (accounts and account types, products and units, ...), pass a
 * record name instead (e.g. {@code "ACCOUNT_TYPE"}), so two rows with the same id never look alike.
 */
public class AuditService {

    public void log(Session session, AuditAction action, AppModule module, Long referenceId,
                    String oldValue, String newValue) {
        log(session, action, module.name(), referenceId, oldValue, newValue);
    }

    public void log(Session session, AuditAction action, String record, Long referenceId,
                    String oldValue, String newValue) {
        AuditLog row = new AuditLog();
        if (UserSession.isLoggedIn()) {
            row.setUser(session.getReference(User.class, UserSession.get().getUserId()));
        }
        row.setAction(action.name());
        row.setModule(record);
        row.setReferenceId(referenceId);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        session.persist(row);
    }

    public void created(Session session, AppModule module, Long referenceId, String newValue) {
        created(session, module.name(), referenceId, newValue);
    }

    public void updated(Session session, AppModule module, Long referenceId, String oldValue, String newValue) {
        updated(session, module.name(), referenceId, oldValue, newValue);
    }

    public void deleted(Session session, AppModule module, Long referenceId, String oldValue) {
        deleted(session, module.name(), referenceId, oldValue);
    }

    public void created(Session session, String record, Long referenceId, String newValue) {
        log(session, AuditAction.CREATE, record, referenceId, null, newValue);
    }

    public void updated(Session session, String record, Long referenceId, String oldValue, String newValue) {
        log(session, AuditAction.UPDATE, record, referenceId, oldValue, newValue);
    }

    public void deleted(Session session, String record, Long referenceId, String oldValue) {
        log(session, AuditAction.DELETE, record, referenceId, oldValue, null);
    }
}
