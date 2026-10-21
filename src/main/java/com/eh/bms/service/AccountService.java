package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountStatus;
import com.eh.bms.model.AccountType;
import com.eh.bms.model.AppModule;
import com.eh.bms.repository.AccountRepository;
import com.eh.bms.repository.AccountTypeRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** Money accounts (7.1). Each has a Bangla and an English name and a user-managed type. Balances: {@link AccountLedgerService}. */
public class AccountService {

    public enum SaveResult {
        SAVED, DUPLICATE_NAME_BN, DUPLICATE_NAME_EN
    }

    public enum StatusResult {
        DONE, HAS_BALANCE, NOT_FOUND
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    /** Values from the form. {@code id} is null for a new account. Number is dropped when the type needs none. */
    public record AccountData(Long id, String nameBn, String nameEn, long typeId, String number,
                              BigDecimal openingBalance) {
    }

    private final AccountRepository repository = new AccountRepository();
    private final AccountTypeRepository typeRepository = new AccountTypeRepository();
    private final AuditService auditService = new AuditService();
    private final AccountLedgerService ledgerService = new AccountLedgerService();

    public PageResult<Account> page(int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, offset, limit), repository.count(session)));
    }

    /** Every account, sorted by name (e.g. for the transfer form). */
    public List<Account> all() {
        return TransactionHelper.inTransaction(repository::all);
    }

    /** Switches an account off or on. Off only while its balance is exactly 0 (user decision). */
    public StatusResult setStatus(long id, AccountStatus status) {
        return TransactionHelper.inTransaction(session -> {
            Account a = repository.findById(session, id).orElse(null);
            if (a == null) {
                return StatusResult.NOT_FOUND;
            }
            if (status == AccountStatus.INACTIVE && ledgerService.balance(session, id).signum() != 0) {
                return StatusResult.HAS_BALANCE;
            }
            if (a.getStatus() != status) {
                String old = describe(a);
                a.setStatus(status);
                auditService.updated(session, AppModule.ACCOUNT, id, old, describe(a));
            }
            return StatusResult.DONE;
        });
    }

    /** True once the account has ledger entries; the opening balance can no longer change. */
    public boolean hasTransactions(long id) {
        return TransactionHelper.inTransaction(session -> repository.hasTransactions(session, id));
    }

    public SaveResult save(AccountData data) {
        return TransactionHelper.inTransaction(session -> {
            String nameBn = data.nameBn().trim();
            String nameEn = data.nameEn().trim();
            if (repository.existsByName(session, nameBn, data.id())) {
                return SaveResult.DUPLICATE_NAME_BN;
            }
            if (repository.existsByNameEn(session, nameEn, data.id())) {
                return SaveResult.DUPLICATE_NAME_EN;
            }
            AccountType type = typeRepository.findById(session, data.typeId())
                    .orElseThrow(() -> new IllegalStateException("Account type not found: " + data.typeId()));
            String number = type.getNumberRule() == AccountNumberRule.NONE
                    || data.number() == null || data.number().isBlank() ? null : data.number().trim();

            if (data.id() == null) {
                Account a = new Account();
                a.setName(nameBn);
                a.setNameEn(nameEn);
                a.setType(type);
                a.setAccountNumber(number);
                a.setOpeningBalance(data.openingBalance());
                repository.save(session, a);
                auditService.created(session, AppModule.ACCOUNT, a.getId(), describe(a));
                return SaveResult.SAVED;
            }

            Account a = repository.findById(session, data.id())
                    .orElseThrow(() -> new IllegalStateException("Account not found: " + data.id()));
            String old = describe(a);
            a.setName(nameBn);
            a.setNameEn(nameEn);
            a.setType(type);
            a.setAccountNumber(number);
            // The opening balance is fixed once there are ledger entries (balances after it depend on it)
            if (!repository.hasTransactions(session, a.getId())) {
                a.setOpeningBalance(data.openingBalance());
            }
            String now = describe(a);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, AppModule.ACCOUNT, a.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** True if {@link #delete} would delete the account: nothing uses it and its opening balance is 0. */
    public boolean canDelete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Account a = repository.findById(session, id).orElse(null);
            return a != null && !repository.isUsed(session, id) && a.getOpeningBalance().signum() == 0;
        });
    }

    /** Deletes only an account nothing uses, with opening balance 0 (money in it is history too). */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Account a = repository.findById(session, id).orElse(null);
            if (a == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id) || a.getOpeningBalance().signum() != 0) {
                return DeleteResult.IN_USE;
            }
            String old = describe(a);
            repository.delete(session, a);
            auditService.deleted(session, AppModule.ACCOUNT, id, old);
            return DeleteResult.DELETED;
        });
    }

    private static String describe(Account a) {
        return new StringJoiner(", ")
                .add("name=" + a.getName())
                .add("nameEn=" + a.getNameEn())
                .add("type=" + (a.getType() == null ? null : a.getType().getNameEn()))
                .add("number=" + a.getAccountNumber())
                .add("status=" + a.getStatus())
                .add("opening=" + (a.getOpeningBalance() == null ? null : a.getOpeningBalance().toPlainString()))
                .toString();
    }
}
