package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Unit;
import com.eh.bms.repository.UnitRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Units products are counted in (8.4). Each has a Bangla and an English name. */
public class UnitService {

    public enum SaveResult {
        SAVED, DUPLICATE_NAME_BN, DUPLICATE_NAME_EN
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    /** Values from the form. {@code id} is null for a new unit. */
    public record UnitData(Long id, String nameBn, String nameEn) {
    }

    private final UnitRepository repository = new UnitRepository();
    private final AuditService auditService = new AuditService();

    public PageResult<Unit> page(int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, offset, limit), repository.count(session)));
    }

    /** Every unit, in the order they were added (for pick lists, e.g. the product form). */
    public List<Unit> all() {
        return TransactionHelper.inTransaction(repository::all);
    }

    /** Number of products using each of the given units (missing id = none). */
    public Map<Long, Long> productCounts(List<Unit> units) {
        List<Long> ids = units.stream().map(Unit::getId).toList();
        return TransactionHelper.inTransaction(session -> repository.productCounts(session, ids));
    }

    public SaveResult save(UnitData data) {
        return TransactionHelper.inTransaction(session -> {
            String nameBn = data.nameBn().trim();
            String nameEn = data.nameEn().trim();
            if (repository.existsByNameBn(session, nameBn, data.id())) {
                return SaveResult.DUPLICATE_NAME_BN;
            }
            if (repository.existsByNameEn(session, nameEn, data.id())) {
                return SaveResult.DUPLICATE_NAME_EN;
            }
            if (data.id() == null) {
                Unit u = new Unit();
                u.setNameBn(nameBn);
                u.setNameEn(nameEn);
                repository.save(session, u);
                auditService.created(session, AppModule.PRODUCT, u.getId(), describe(u));
                return SaveResult.SAVED;
            }
            Unit u = repository.findById(session, data.id())
                    .orElseThrow(() -> new IllegalStateException("Unit not found: " + data.id()));
            String old = describe(u);
            u.setNameBn(nameBn);
            u.setNameEn(nameEn);
            String now = describe(u);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, AppModule.PRODUCT, u.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** Deletes only a unit no product uses (as unit or secondary unit). */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Unit u = repository.findById(session, id).orElse(null);
            if (u == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(u);
            repository.delete(session, u);
            auditService.deleted(session, AppModule.PRODUCT, id, old);
            return DeleteResult.DELETED;
        });
    }

    private static String describe(Unit u) {
        return "unit: nameBn=" + u.getNameBn() + ", nameEn=" + u.getNameEn();
    }
}
