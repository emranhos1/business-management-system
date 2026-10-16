package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.BalanceType;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.model.PartyTradeType;
import com.eh.bms.model.PartyType;
import com.eh.bms.repository.PartyRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/** Customers and suppliers (10.1). Phone is required and unique. Balances: {@link PartyLedgerService}. */
public class PartyService {

    public enum SaveResult {
        SAVED, DUPLICATE_PHONE
    }

    public enum DeleteResult {
        DELETED, IN_USE, HAS_OPENING, NOT_FOUND
    }

    /** Values from the form. {@code id} is null for a new party; {@code tradeType} may be null. */
    public record PartyData(Long id, String name, String nameEn, String phone, String address, PartyType type,
                            PartyTradeType tradeType, BigDecimal openingBalance, BalanceType balanceType) {
    }

    private final PartyRepository repository = new PartyRepository();
    private final AuditService auditService = new AuditService();

    public enum StatusResult {
        DONE, HAS_BALANCE, NOT_FOUND
    }

    private final PartyLedgerService ledgerService = new PartyLedgerService();

    /** One page of parties with this status, sorted by name; {@code search} (may be blank) matches name or phone. */
    public PageResult<Party> page(PartyStatus status, String search, int offset, int limit) {
        return TransactionHelper.inTransaction(session -> new PageResult<>(
                repository.page(session, status, search, offset, limit), repository.count(session, status, search)));
    }

    /**
     * Makes a party active or inactive. An inactive party leaves the lists and pickers but keeps its ledger.
     * A party with money due (পাবো / দেবো) cannot be made inactive, so no due is hidden.
     */
    public StatusResult setStatus(long id, PartyStatus status) {
        return TransactionHelper.inTransaction(session -> {
            Party p = repository.findById(session, id).orElse(null);
            if (p == null) {
                return StatusResult.NOT_FOUND;
            }
            if (status == PartyStatus.INACTIVE && ledgerService.balance(session, id).signum() != 0) {
                return StatusResult.HAS_BALANCE;
            }
            if (p.getStatus() != status) {
                String old = describe(p);
                p.setStatus(status);
                auditService.updated(session, AppModule.PARTY, id, old, describe(p));
            }
            return StatusResult.DONE;
        });
    }

    /** The party with this mobile number (unique), e.g. one just added. */
    public Optional<Party> findByPhone(String phone) {
        return TransactionHelper.inTransaction(session -> repository.findByPhone(session, phone.trim()));
    }

    /** True once the party has ledger entries; the opening balance can no longer change. */
    public boolean hasLedger(long id) {
        return TransactionHelper.inTransaction(session -> repository.hasLedger(session, id));
    }

    public SaveResult save(PartyData data) {
        return TransactionHelper.inTransaction(session -> {
            String phone = data.phone().trim();
            if (repository.existsByPhone(session, phone, data.id())) {
                return SaveResult.DUPLICATE_PHONE;
            }
            if (data.id() == null) {
                Party p = new Party();
                fill(p, data, phone);
                p.setOpeningBalance(data.openingBalance());
                p.setBalanceType(data.balanceType());
                repository.save(session, p);
                auditService.created(session, AppModule.PARTY, p.getId(), describe(p));
                return SaveResult.SAVED;
            }

            Party p = repository.findById(session, data.id())
                    .orElseThrow(() -> new IllegalStateException("Party not found: " + data.id()));
            String old = describe(p);
            fill(p, data, phone);
            // The opening balance is fixed once there are ledger entries (balances after it depend on it)
            if (!repository.hasLedger(session, p.getId())) {
                p.setOpeningBalance(data.openingBalance());
                p.setBalanceType(data.balanceType());
            }
            String now = describe(p);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, AppModule.PARTY, p.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** True if {@link #delete} would delete the party: no earlier balance, ledger entries, sales or purchases. */
    public boolean canDelete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Party p = repository.findById(session, id).orElse(null);
            return p != null && !repository.isUsed(session, id)
                    && (p.getOpeningBalance() == null || p.getOpeningBalance().signum() == 0);
        });
    }

    /** Deletes only a party with no earlier balance, ledger entries, sales or purchases. */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Party p = repository.findById(session, id).orElse(null);
            if (p == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            // An earlier balance (আগের হিসাব) is money due, so the party is already in use
            if (p.getOpeningBalance() != null && p.getOpeningBalance().signum() != 0) {
                return DeleteResult.HAS_OPENING;
            }
            String old = describe(p);
            repository.delete(session, p);
            auditService.deleted(session, AppModule.PARTY, id, old);
            return DeleteResult.DELETED;
        });
    }

    private static void fill(Party p, PartyData data, String phone) {
        p.setName(data.name().trim());
        p.setNameEn(data.nameEn().trim());
        p.setPhone(phone);
        p.setAddress(data.address() == null || data.address().isBlank() ? null : data.address().trim());
        p.setType(data.type());
        p.setPartyType(data.tradeType());
    }

    private static String describe(Party p) {
        return new StringJoiner(", ")
                .add("name=" + p.getName())
                .add("nameEn=" + p.getNameEn())
                .add("phone=" + p.getPhone())
                .add("address=" + p.getAddress())
                .add("type=" + p.getType())
                .add("tradeType=" + p.getPartyType())
                .add("opening=" + (p.getOpeningBalance() == null ? null : p.getOpeningBalance().toPlainString()))
                .add("balanceType=" + p.getBalanceType())
                .add("status=" + p.getStatus())
                .toString();
    }
}
