package com.eh.bms.service;

import com.eh.bms.model.AuditLog;
import com.eh.bms.repository.AuditLogRepository;
import com.eh.bms.repository.AuditLogRepository.Filter;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** The audit log viewer (17.4): who did what and when. Read-only, and for the admin only. */
public class AuditLogService {

    /** One log line; {@code user} is null for a change made by the system (e.g. startup seeding). */
    public record Row(long id, LocalDateTime time, String user, String action, String module, Long referenceId,
                      String oldValue, String newValue) {
    }

    private final AuditLogRepository repository = new AuditLogRepository();

    /**
     * Changes of the dates {@code from}..{@code to} (whole days; null = no limit), by one user and in one module
     * or record kind (null = all), newest first.
     */
    public PageResult<Row> page(LocalDate from, LocalDate to, Long userId, String module, int offset, int limit) {
        requireAdmin();
        Filter filter = new Filter(from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(), userId, module);
        return TransactionHelper.inTransaction(session -> {
            List<Row> rows = repository.page(session, filter, offset, limit).stream().map(AuditLogService::toRow).toList();
            return new PageResult<>(rows, repository.count(session, filter));
        });
    }

    /** Modules and record kinds that appear in the log. */
    public List<String> modules() {
        requireAdmin();
        return TransactionHelper.inTransaction(repository::modules);
    }

    private static Row toRow(AuditLog a) {
        return new Row(a.getId(), a.getTimestamp(), a.getUser() == null ? null : a.getUser().getName(), a.getAction(),
                a.getModule(), a.getReferenceId(), a.getOldValue(), a.getNewValue());
    }

    private static void requireAdmin() {
        if (!UserSession.isLoggedIn() || !UserSession.get().isAdmin()) {
            throw new IllegalStateException("Only an admin may read the audit log");
        }
    }
}
