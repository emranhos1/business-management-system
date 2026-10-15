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
 */
public class AuditService {

    public void log(Session session, AuditAction action, AppModule module, Long referenceId,
                    String oldValue, String newValue) {
        AuditLog row = new AuditLog();
        if (UserSession.isLoggedIn()) {
            row.setUser(session.getReference(User.class, UserSession.get().getUserId()));
        }
        row.setAction(action.name());
        row.setModule(module.name());
        row.setReferenceId(referenceId);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        session.persist(row);
    }

    public void created(Session session, AppModule module, Long referenceId, String newValue) {
        log(session, AuditAction.CREATE, module, referenceId, null, newValue);
    }

    public void updated(Session session, AppModule module, Long referenceId, String oldValue, String newValue) {
        log(session, AuditAction.UPDATE, module, referenceId, oldValue, newValue);
    }

    public void deleted(Session session, AppModule module, Long referenceId, String oldValue) {
        log(session, AuditAction.DELETE, module, referenceId, oldValue, null);
    }
}
