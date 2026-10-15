package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedger;
import com.eh.bms.repository.AccountTransactionRepository;
import com.eh.bms.repository.AccountTransactionRepository.Totals;
import com.eh.bms.repository.PartyLedgerRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Account ledgers (7.2) and balances (7.3).
 * Balance = opening balance + money in - money out.
 * Every module that moves money writes its entries through {@link #post}, inside its own transaction.
 */
public class AccountLedgerService {

    /**
     * One ledger line. {@code otherAccount} is set for transfers (the account on the other side),
     * {@code party} for money paid to or received from a party.
     */
    public record LedgerRow(long id, LocalDate date, AccountTransactionType type, BigDecimal amount,
                            BigDecimal balance, AccountTransactionSource source, Account otherAccount,
                            Party party, String note) {
        public BigDecimal moneyIn() {
            return type == AccountTransactionType.CREDIT ? amount : null;
        }

        public BigDecimal moneyOut() {
            return type == AccountTransactionType.DEBIT ? amount : null;
        }
    }

    /** Figures shown above the ledger. */
    public record Summary(BigDecimal opening, BigDecimal totalIn, BigDecimal totalOut, BigDecimal balance) {
    }

    private final AccountTransactionRepository repository = new AccountTransactionRepository();
    private final PartyLedgerRepository partyLedgerRepository = new PartyLedgerRepository();

    public BigDecimal balance(long accountId) {
        return TransactionHelper.inTransaction(session -> balance(session, accountId));
    }

    public BigDecimal balance(Session session, long accountId) {
        Account account = session.find(Account.class, accountId);
        if (account == null) {
            throw new IllegalStateException("Account not found: " + accountId);
        }
        return account.getOpeningBalance().add(repository.totals(session, accountId).net());
    }

    /** Current balance of each account. */
    public Map<Long, BigDecimal> balances(Collection<Account> accounts) {
        List<Long> ids = accounts.stream().map(Account::getId).toList();
        Map<Long, BigDecimal> net = TransactionHelper.inTransaction(session -> repository.netByAccount(session, ids));
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Account a : accounts) {
            result.put(a.getId(), a.getOpeningBalance().add(net.getOrDefault(a.getId(), BigDecimal.ZERO)));
        }
        return result;
    }

    public Summary summary(long accountId) {
        return TransactionHelper.inTransaction(session -> {
            Account account = session.find(Account.class, accountId);
            Totals totals = repository.totals(session, accountId);
            BigDecimal opening = account.getOpeningBalance();
            return new Summary(opening, totals.in(), totals.out(), opening.add(totals.net()));
        });
    }

    /**
     * One page of the ledger, newest first. The balance column is worked out by date order
     * (current balance minus everything after the row), so back-dated entries still show right.
     */
    public PageResult<LedgerRow> page(long accountId, int offset, int limit) {
        return TransactionHelper.inTransaction(session -> {
            List<AccountTransaction> entries = repository.page(session, accountId, offset, limit);
            long total = repository.count(session, accountId);
            if (entries.isEmpty()) {
                return new PageResult<>(List.of(), total);
            }

            // The other side of transfers, to name the other account
            List<Long> otherIds = entries.stream()
                    .filter(t -> t.getSource() == AccountTransactionSource.TRANSFER && t.getReferenceId() != null)
                    .map(AccountTransaction::getReferenceId)
                    .toList();
            Map<Long, AccountTransaction> others = repository.findByIds(session, otherIds);
            // Party payments: the party ledger entry names the party
            List<Long> partyEntryIds = entries.stream()
                    .filter(t -> t.getSource() == AccountTransactionSource.PARTY && t.getReferenceId() != null)
                    .map(AccountTransaction::getReferenceId)
                    .toList();
            Map<Long, PartyLedger> partyEntries = partyLedgerRepository.findByIds(session, partyEntryIds);

            AccountTransaction first = entries.get(0);
            BigDecimal balance = balance(session, accountId)
                    .subtract(repository.netAfter(session, accountId, first.getDate(), first.getId()));

            List<LedgerRow> rows = new ArrayList<>(entries.size());
            for (AccountTransaction t : entries) {
                boolean linked = t.getReferenceId() != null;
                AccountTransaction other = linked && t.getSource() == AccountTransactionSource.TRANSFER
                        ? others.get(t.getReferenceId()) : null;
                PartyLedger partyEntry = linked && t.getSource() == AccountTransactionSource.PARTY
                        ? partyEntries.get(t.getReferenceId()) : null;
                rows.add(new LedgerRow(t.getId(), t.getDate(), t.getType(), t.getAmount(), balance,
                        t.getSource(), other == null ? null : other.getAccount(),
                        partyEntry == null ? null : partyEntry.getParty(), t.getNote()));
                balance = balance.subtract(signed(t));
            }
            return new PageResult<>(rows, total);
        });
    }

    /**
     * Writes one ledger entry. The account row is locked first, so two entries posted at the same
     * time cannot both read the same old balance. Call inside the caller's transaction.
     */
    public AccountTransaction post(Session session, long accountId, LocalDate date, AccountTransactionType type,
                                   BigDecimal amount, AccountTransactionSource source, Long referenceId,
                                   String note) {
        Objects.requireNonNull(date, "date");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        Account account = lock(session, accountId);
        BigDecimal before = account.getOpeningBalance().add(repository.totals(session, accountId).net());

        AccountTransaction t = new AccountTransaction();
        t.setAccount(account);
        t.setDate(date);
        t.setType(type);
        t.setAmount(amount);
        t.setBalanceAfter(type == AccountTransactionType.CREDIT ? before.add(amount) : before.subtract(amount));
        t.setSource(source);
        t.setReferenceId(referenceId);
        t.setNote(note == null || note.isBlank() ? null : note.trim());
        repository.save(session, t);
        return t;
    }

    /** Locks the account row until the transaction ends. */
    public Account lock(Session session, long accountId) {
        Account account = session.find(Account.class, accountId, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) {
            throw new IllegalStateException("Account not found: " + accountId);
        }
        return account;
    }

    private static BigDecimal signed(AccountTransaction t) {
        return t.getType() == AccountTransactionType.CREDIT ? t.getAmount() : t.getAmount().negate();
    }
}
