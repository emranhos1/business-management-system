package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.BalanceType;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedger;
import com.eh.bms.model.PartyLedgerType;
import com.eh.bms.repository.AccountTransactionRepository;
import com.eh.bms.repository.PartyLedgerRepository;
import com.eh.bms.repository.PartyLedgerRepository.Totals;
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
 * Party ledgers (10.2) and balances (10.4).
 * Balance = opening (+ receivable, - payable) + debit - credit. Positive: the party owes us (পাবো).
 * Negative: we owe the party (দেবো). Debit = what we gave them (goods sold, money paid);
 * credit = what we got from them (goods bought, money received).
 * Every module that changes what a party owes writes its entries through {@link #post}, inside its own transaction.
 */
public class PartyLedgerService {

    /** One ledger line. {@code account} is set for money paid or received (the account it went through). */
    public record LedgerRow(long id, LocalDate date, PartyLedgerType type, BigDecimal debit, BigDecimal credit,
                            BigDecimal balance, Account account, String note) {
        /** What we gave, or null. */
        public BigDecimal gave() {
            return debit.signum() > 0 ? debit : null;
        }

        /** What we got, or null. */
        public BigDecimal got() {
            return credit.signum() > 0 ? credit : null;
        }
    }

    /** Figures shown above the ledger. {@code opening} and {@code balance} are signed (+ পাবো, - দেবো). */
    public record Summary(BigDecimal opening, BigDecimal totalGave, BigDecimal totalGot, BigDecimal balance) {
    }

    private final PartyLedgerRepository repository = new PartyLedgerRepository();
    private final AccountTransactionRepository accountRepository = new AccountTransactionRepository();

    /** Opening balance with its sign: + the party owed us, - we owed the party. */
    public static BigDecimal signedOpening(Party p) {
        BigDecimal opening = p.getOpeningBalance() == null ? BigDecimal.ZERO : p.getOpeningBalance();
        return p.getBalanceType() == BalanceType.PAYABLE ? opening.negate() : opening;
    }

    public BigDecimal balance(Session session, long partyId) {
        Party party = session.find(Party.class, partyId);
        if (party == null) {
            throw new IllegalStateException("Party not found: " + partyId);
        }
        return signedOpening(party).add(repository.totals(session, partyId).net());
    }

    public BigDecimal balance(long partyId) {
        return TransactionHelper.inTransaction(session -> balance(session, partyId));
    }

    /** Current balance of each party. */
    public Map<Long, BigDecimal> balances(Collection<Party> parties) {
        List<Long> ids = parties.stream().map(Party::getId).toList();
        Map<Long, BigDecimal> net = TransactionHelper.inTransaction(session -> repository.netByParty(session, ids));
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Party p : parties) {
            result.put(p.getId(), signedOpening(p).add(net.getOrDefault(p.getId(), BigDecimal.ZERO)));
        }
        return result;
    }

    public Summary summary(long partyId) {
        return TransactionHelper.inTransaction(session -> {
            Party party = session.find(Party.class, partyId);
            Totals totals = repository.totals(session, partyId);
            BigDecimal opening = signedOpening(party);
            return new Summary(opening, totals.debit(), totals.credit(), opening.add(totals.net()));
        });
    }

    /**
     * One page of the ledger, newest first. The balance column is worked out by date order
     * (current balance minus everything after the row), so back-dated entries still show right.
     */
    public PageResult<LedgerRow> page(long partyId, int offset, int limit) {
        return TransactionHelper.inTransaction(session -> {
            List<PartyLedger> entries = repository.page(session, partyId, offset, limit);
            long total = repository.count(session, partyId);
            if (entries.isEmpty()) {
                return new PageResult<>(List.of(), total);
            }

            // Money paid or received: the account entry names the account
            List<Long> accountEntryIds = entries.stream()
                    .filter(l -> isMoney(l.getType()) && l.getReferenceId() != null)
                    .map(PartyLedger::getReferenceId)
                    .toList();
            Map<Long, AccountTransaction> accountEntries = accountRepository.findByIds(session, accountEntryIds);

            PartyLedger first = entries.get(0);
            BigDecimal balance = balance(session, partyId)
                    .subtract(repository.netAfter(session, partyId, first.getDate(), first.getId()));

            List<LedgerRow> rows = new ArrayList<>(entries.size());
            for (PartyLedger l : entries) {
                AccountTransaction t = isMoney(l.getType()) && l.getReferenceId() != null
                        ? accountEntries.get(l.getReferenceId()) : null;
                rows.add(new LedgerRow(l.getId(), l.getDate(), l.getType(), l.getDebit(), l.getCredit(), balance,
                        t == null ? null : t.getAccount(), l.getNote()));
                balance = balance.subtract(l.getDebit().subtract(l.getCredit()));
            }
            return new PageResult<>(rows, total);
        });
    }

    /**
     * Writes one ledger entry: {@code debit} = what we gave, {@code credit} = what we got (one may be zero).
     * The party row is locked first, so two entries posted at the same time cannot both read the same
     * old balance. Call inside the caller's transaction.
     */
    public PartyLedger post(Session session, long partyId, LocalDate date, PartyLedgerType type,
                            BigDecimal debit, BigDecimal credit, Long referenceId, String note) {
        Objects.requireNonNull(date, "date");
        BigDecimal d = debit == null ? BigDecimal.ZERO : debit;
        BigDecimal c = credit == null ? BigDecimal.ZERO : credit;
        if (d.signum() < 0 || c.signum() < 0 || (d.signum() == 0 && c.signum() == 0)) {
            throw new IllegalArgumentException("Debit/credit must be 0 or more, and not both 0");
        }
        Party party = lock(session, partyId);
        BigDecimal before = signedOpening(party).add(repository.totals(session, partyId).net());

        PartyLedger l = new PartyLedger();
        l.setParty(party);
        l.setDate(date);
        l.setType(type);
        l.setDebit(d);
        l.setCredit(c);
        l.setBalance(before.add(d).subtract(c));
        l.setReferenceId(referenceId);
        l.setNote(note == null || note.isBlank() ? null : note.trim());
        repository.save(session, l);
        return l;
    }

    /** Locks the party row until the transaction ends. */
    public Party lock(Session session, long partyId) {
        Party party = session.find(Party.class, partyId, LockModeType.PESSIMISTIC_WRITE);
        if (party == null) {
            throw new IllegalStateException("Party not found: " + partyId);
        }
        return party;
    }

    /** Entries for money paid or received; their {@code reference_id} is the account ledger entry. */
    private static boolean isMoney(PartyLedgerType type) {
        return type == PartyLedgerType.PAYMENT || type == PartyLedgerType.RECEIPT;
    }
}
