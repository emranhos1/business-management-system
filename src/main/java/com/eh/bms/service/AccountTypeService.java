package com.eh.bms.service;

import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountType;
import com.eh.bms.repository.AccountTypeRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.util.List;
import java.util.Objects;

/** Account types the user manages (7.1). */
public class AccountTypeService {

    public enum SaveResult {
        SAVED, DUPLICATE_NAME_BN, DUPLICATE_NAME_EN
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    /** Values from the form. {@code id} is null for a new type. */
    public record TypeData(Long id, String nameBn, String nameEn, AccountNumberRule numberRule) {
    }

    private final AccountTypeRepository repository = new AccountTypeRepository();
    private final AuditService auditService = new AuditService();

    /** All types, for the account form's list. */
    public List<AccountType> all() {
        return TransactionHelper.inTransaction(repository::findAll);
    }

    public PageResult<AccountType> page(int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, offset, limit), repository.count(session)));
    }

    public SaveResult save(TypeData data) {
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
                AccountType t = new AccountType();
                t.setNameBn(nameBn);
                t.setNameEn(nameEn);
                t.setNumberRule(data.numberRule());
                repository.save(session, t);
                auditService.created(session, "ACCOUNT_TYPE", t.getId(), describe(t));
                return SaveResult.SAVED;
            }
            AccountType t = repository.findById(session, data.id())
                    .orElseThrow(() -> new IllegalStateException("Account type not found: " + data.id()));
            String old = describe(t);
            t.setNameBn(nameBn);
            t.setNameEn(nameEn);
            // The user may change the rule any time; existing accounts follow it on their next edit
            t.setNumberRule(data.numberRule());
            String now = describe(t);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, "ACCOUNT_TYPE", t.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** True if no account uses the type, so {@link #delete} would delete it. */
    public boolean canDelete(long id) {
        return TransactionHelper.inTransaction(session -> !repository.isUsed(session, id));
    }

    /** Deletes only a type no account uses. */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            AccountType t = repository.findById(session, id).orElse(null);
            if (t == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(t);
            repository.delete(session, t);
            auditService.deleted(session, "ACCOUNT_TYPE", id, old);
            return DeleteResult.DELETED;
        });
    }

    private static String describe(AccountType t) {
        return "accountType: nameBn=" + t.getNameBn() + ", nameEn=" + t.getNameEn() + ", rule=" + t.getNumberRule();
    }
}
