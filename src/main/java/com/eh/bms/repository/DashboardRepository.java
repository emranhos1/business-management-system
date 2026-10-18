package com.eh.bms.repository;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.Party;
import com.eh.bms.model.Product;
import com.eh.bms.model.StockLedger;
import com.eh.bms.model.StockLedgerType;
import com.eh.bms.model.TransactionStatus;
import com.eh.bms.model.TransactionType;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only totals for the dashboard (Stage 15). */
public class DashboardRepository {

    /** Bill total of the confirmed sales or purchases dated {@code from}..{@code to}. */
    public BigDecimal billTotal(Session session, TransactionType type, LocalDate from, LocalDate to) {
        return session.createSelectionQuery("select coalesce(sum(t.grandTotal), 0) from BusinessTransaction t"
                        + " where t.type = :type and t.status = :confirmed and t.date >= :from and t.date <= :to",
                        BigDecimal.class)
                .setParameter("type", type)
                .setParameter("confirmed", TransactionStatus.CONFIRMED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }

    /** What the confirmed sales dated {@code from}..{@code to} are still worth: bill total minus goods returned. */
    public BigDecimal netSales(Session session, LocalDate from, LocalDate to) {
        return session.createSelectionQuery("select coalesce(sum(t.grandTotal - coalesce(t.returnedAmount, 0)), 0)"
                        + " from BusinessTransaction t where t.type = :type and t.status = :confirmed"
                        + " and t.date >= :from and t.date <= :to", BigDecimal.class)
                .setParameter("type", TransactionType.SALE)
                .setParameter("confirmed", TransactionStatus.CONFIRMED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }

    /**
     * Cost of the goods those sales took out of stock (average cost when sold), minus the cost of the goods that
     * came back from them, at the same price they left at (the sale's own SALE entry for that product), so a
     * later change of the average cost never makes a return look like profit or loss.
     */
    public BigDecimal costOfSales(Session session, LocalDate from, LocalDate to) {
        String salesOfDates = "select t.id from BusinessTransaction t where t.type = :type and t.status = :confirmed"
                + " and t.date >= :from and t.date <= :to";
        BigDecimal out = session.createSelectionQuery("select coalesce(sum(-s.quantity * s.unitPrice), 0)"
                        + " from StockLedger s where s.type = :sale and s.referenceId in (" + salesOfDates + ")",
                        BigDecimal.class)
                .setParameter("sale", StockLedgerType.SALE)
                .setParameter("type", TransactionType.SALE)
                .setParameter("confirmed", TransactionStatus.CONFIRMED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        BigDecimal back = session.createSelectionQuery("select coalesce(sum(r.quantity * coalesce((select max(s.unitPrice)"
                        + " from StockLedger s where s.type = :sale and s.referenceId = r.referenceId"
                        + " and s.product = r.product), r.unitPrice)), 0)"
                        + " from StockLedger r where r.type = :saleReturn and r.referenceId in (" + salesOfDates + ")",
                        BigDecimal.class)
                .setParameter("sale", StockLedgerType.SALE)
                .setParameter("saleReturn", StockLedgerType.SALE_RETURN)
                .setParameter("type", TransactionType.SALE)
                .setParameter("confirmed", TransactionStatus.CONFIRMED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return out.subtract(back);
    }

    /** Net salary of the salary sheets of the months {@code from}..{@code to} (worked out, paid or not). */
    public BigDecimal salaryOfMonths(Session session, YearMonth from, YearMonth to) {
        return session.createSelectionQuery("select coalesce(sum(p.netSalary), 0) from SalaryProcessing p"
                        + " where p.year * 12 + p.month >= :from and p.year * 12 + p.month <= :to", BigDecimal.class)
                .setParameter("from", from.getYear() * 12 + from.getMonthValue())
                .setParameter("to", to.getYear() * 12 + to.getMonthValue())
                .getSingleResult();
    }

    /**
     * Money that came into ({@code CREDIT}) or went out of ({@code DEBIT}) all accounts dated {@code from}..{@code to},
     * transfers left out.
     */
    public BigDecimal money(Session session, LocalDate from, LocalDate to, AccountTransactionType type) {
        return session.createSelectionQuery("select coalesce(sum(t.amount), 0) from AccountTransaction t"
                        + " where t.date >= :from and t.date <= :to and t.type = :type"
                        + " and (t.source is null or t.source <> :transfer)", BigDecimal.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("type", type)
                .setParameter("transfer", AccountTransactionSource.TRANSFER)
                .getSingleResult();
    }

    /** Sum of the expenses dated {@code from}..{@code to}. */
    public BigDecimal expenses(Session session, LocalDate from, LocalDate to) {
        return session.createSelectionQuery("select coalesce(sum(e.amount), 0) from Expense e"
                        + " where e.date >= :from and e.date <= :to", BigDecimal.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }

    /** Every party, active or not (an inactive party may still owe or be owed). */
    public List<Party> allParties(Session session) {
        return session.createSelectionQuery("from Party p", Party.class).getResultList();
    }

    /** Ledger net (debit - credit) of each party from its entries dated on or before {@code date}. */
    public Map<Long, BigDecimal> partyNetUpTo(Session session, LocalDate date) {
        Map<Long, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("select l.party.id, sum(l.debit - l.credit) from PartyLedger l"
                        + " where l.date <= :date group by l.party.id", Object[].class)
                .setParameter("date", date)
                .getResultList()
                .forEach(r -> result.put((Long) r[0], (BigDecimal) r[1]));
        return result;
    }

    /** All accounts by name (the Accounts page order). */
    public List<Account> allAccounts(Session session) {
        return session.createSelectionQuery("from Account a left join fetch a.type order by a.name", Account.class)
                .getResultList();
    }

    /** Net (in - out) of each account from its entries dated on or before {@code date}. */
    public Map<Long, BigDecimal> accountNetUpTo(Session session, LocalDate date) {
        Map<Long, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("select t.account.id, sum(case when t.type = :credit then t.amount else -t.amount end)"
                        + " from AccountTransaction t where t.date <= :date group by t.account.id", Object[].class)
                .setParameter("credit", AccountTransactionType.CREDIT)
                .setParameter("date", date)
                .getResultList()
                .forEach(r -> result.put((Long) r[0], (BigDecimal) r[1]));
        return result;
    }

    /** All products sorted by name, with category and units loaded. */
    public List<Product> allProducts(Session session) {
        return session.createSelectionQuery("from Product p join fetch p.category left join fetch p.unit"
                + " left join fetch p.secondaryUnit order by p.name", Product.class).getResultList();
    }

    /** Stock of each product from its entries dated on or before {@code date} (main unit). */
    public Map<Long, BigDecimal> stockUpTo(Session session, LocalDate date) {
        Map<Long, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("select s.product.id, sum(s.quantity) from StockLedger s"
                        + " where s.date <= :date group by s.product.id", Object[].class)
                .setParameter("date", date)
                .getResultList()
                .forEach(r -> result.put((Long) r[0], (BigDecimal) r[1]));
        return result;
    }

    /**
     * Average cost of each product after its last entry (posting order) dated on or before {@code date}; the same
     * rule the stock pages use for today.
     */
    public Map<Long, BigDecimal> averageCostUpTo(Session session, LocalDate date) {
        Map<Long, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("from StockLedger s where s.id in (select max(s2.id) from StockLedger s2"
                        + " where s2.date <= :date group by s2.product.id)", StockLedger.class)
                .setParameter("date", date)
                .getResultList()
                .forEach(s -> result.put(s.getProduct().getId(), s.getAvgCostAfter()));
        return result;
    }
}
