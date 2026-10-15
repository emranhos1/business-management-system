package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedger;
import com.eh.bms.model.PartyLedgerType;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money received from or paid to a party (10.5), in one transaction:
 * received: party ledger credit (RECEIPT) + money into the account;
 * paid: party ledger debit (PAYMENT) + money out of the account (never below zero).
 * The two entries point at each other ({@code reference_id}).
 */
public class PartyPaymentService {

    public enum Direction {
        /** টাকা পেলাম: the party gave us money. */
        RECEIVED,
        /** টাকা দিলাম: we gave the party money. */
        PAID
    }

    public enum Status {
        DONE, INSUFFICIENT_BALANCE
    }

    /** {@code available} is the account balance, set for INSUFFICIENT_BALANCE. */
    public record Result(Status status, BigDecimal available) {
    }

    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

    public Result record(long partyId, long accountId, Direction direction, BigDecimal amount, LocalDate date,
                         String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        return TransactionHelper.inTransaction(session -> {
            // Always party first, then account, so two payments cannot deadlock
            Party party = partyLedger.lock(session, partyId);
            Account account = accountLedger.lock(session, accountId);
            if (party.getStatus() == PartyStatus.INACTIVE) {
                throw new IllegalStateException("Party is inactive: " + partyId);
            }

            boolean paid = direction == Direction.PAID;
            if (paid) {
                BigDecimal available = accountLedger.balance(session, accountId);
                if (available.compareTo(amount) < 0) {
                    return new Result(Status.INSUFFICIENT_BALANCE, available);
                }
            }

            AccountTransaction money = accountLedger.post(session, accountId, date,
                    paid ? AccountTransactionType.DEBIT : AccountTransactionType.CREDIT, amount,
                    AccountTransactionSource.PARTY, null, note);
            PartyLedger entry = partyLedger.post(session, partyId, date,
                    paid ? PartyLedgerType.PAYMENT : PartyLedgerType.RECEIPT,
                    paid ? amount : BigDecimal.ZERO, paid ? BigDecimal.ZERO : amount, money.getId(), note);
            money.setReferenceId(entry.getId());

            auditService.created(session, AppModule.PARTY, entry.getId(),
                    (paid ? "paid to" : "received from") + " party=" + party.getName() + " (" + party.getPhone() + ")"
                            + " account=" + account.getNameEn() + " amount=" + amount.toPlainString() + " date=" + date
                            + (note == null || note.isBlank() ? "" : " note=" + note.trim()));
            return new Result(Status.DONE, null);
        });
    }
}
