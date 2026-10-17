package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Moves money between two accounts (7.4): money out of one, into the other, in one transaction.
 * Both entries point at each other ({@code reference_id}) and are marked {@code TRANSFER}.
 */
public class TransferService {

    public enum Status {
        DONE, SAME_ACCOUNT, INSUFFICIENT_BALANCE
    }

    /** {@code available} is the from-account balance, set for INSUFFICIENT_BALANCE. */
    public record Result(Status status, BigDecimal available) {
    }

    private final AccountLedgerService ledger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

    public Result transfer(long fromId, long toId, BigDecimal amount, LocalDate date, String note) {
        if (fromId == toId) {
            return new Result(Status.SAME_ACCOUNT, null);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        return TransactionHelper.inTransaction(session -> {
            // Lock both rows in id order, so two transfers between the same accounts cannot deadlock
            Account first = ledger.lock(session, Math.min(fromId, toId));
            Account second = ledger.lock(session, Math.max(fromId, toId));
            Account from = first.getId() == fromId ? first : second;
            Account to = from == first ? second : first;

            BigDecimal available = ledger.availableOn(session, fromId, date);
            if (available.compareTo(amount) < 0) {
                return new Result(Status.INSUFFICIENT_BALANCE, available);
            }

            AccountTransaction out = ledger.post(session, fromId, date, AccountTransactionType.DEBIT, amount,
                    AccountTransactionSource.TRANSFER, null, note);
            AccountTransaction in = ledger.post(session, toId, date, AccountTransactionType.CREDIT, amount,
                    AccountTransactionSource.TRANSFER, out.getId(), note);
            out.setReferenceId(in.getId());

            auditService.created(session, "TRANSFER", out.getId(),
                    "transfer from=" + from.getNameEn() + " to=" + to.getNameEn()
                            + " amount=" + amount.toPlainString() + " date=" + date
                            + (note == null || note.isBlank() ? "" : " note=" + note.trim()));
            return new Result(Status.DONE, null);
        });
    }
}
