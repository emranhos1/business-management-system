package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.Party;
import com.eh.bms.model.Product;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.model.TransactionType;
import com.eh.bms.repository.DashboardRepository;
import com.eh.bms.service.StockReportService.LowStockRow;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard figures (Stage 15) for a date range picked on the page (today at first), read in one transaction.
 * Sales, purchases, expenses, net cash and profit are for the dates of the range; party, account and stock
 * figures are as they stood at the end of its last day.
 * Profit (user decision): sales (minus goods returned from them) − what those goods cost (average cost when they
 * left stock; returns at the same price) − expenses − the worked-out salary of the range's months (paid or not).
 * Net cash: money into all accounts − money out, transfers left out.
 */
public class DashboardService {

    /** Money is shown with 2 decimals; each stock value is rounded so the total matches the rows. */
    private static final int MONEY_DECIMALS = 2;

    /** Figures of the range's dates (15.1). */
    public record Period(BigDecimal sales, BigDecimal purchases, BigDecimal expenses, BigDecimal moneyIn,
                         BigDecimal moneyOut) {
        public BigDecimal netCash() {
            return moneyIn.subtract(moneyOut);
        }
    }

    /** Net profit of the range and its parts (15.4). */
    public record Profit(BigDecimal sales, BigDecimal costOfSales, BigDecimal expenses, BigDecimal salary,
                         BigDecimal writtenOff) {
        public BigDecimal grossProfit() {
            return sales.subtract(costOfSales);
        }

        public BigDecimal net() {
            return grossProfit().subtract(expenses).subtract(salary).subtract(writtenOff);
        }
    }

    /** One product category's stock: products in stock and their value (15.3). */
    public record CategoryStock(ProductCategory category, int products, BigDecimal value) {
    }

    /** An account and its balance (15.6). */
    public record AccountBalance(Account account, BigDecimal balance) {
    }

    /** Everything the dashboard shows. Receivable / payable: sum of party balances above / below 0 (15.2). */
    public record Snapshot(Period period, BigDecimal receivable, int receivableParties, BigDecimal payable,
                           int payableParties, Profit profit, List<CategoryStock> stock, BigDecimal stockValue,
                           List<LowStockRow> lowStock, List<AccountBalance> accounts, BigDecimal accountsTotal) {
    }

    private final DashboardRepository repository = new DashboardRepository();

    /** All figures for the dates {@code from}..{@code to} (one day when both are the same). */
    public Snapshot load(LocalDate from, LocalDate to) {
        return TransactionHelper.inTransaction(session -> {
            Period period = new Period(repository.billTotal(session, TransactionType.SALE, from, to),
                    repository.billTotal(session, TransactionType.PURCHASE, from, to),
                    repository.expenses(session, from, to),
                    repository.money(session, from, to, AccountTransactionType.CREDIT),
                    repository.money(session, from, to, AccountTransactionType.DEBIT));
            Profit profit = new Profit(money(repository.netSales(session, from, to)),
                    money(repository.costOfSales(session, from, to)),
                    period.expenses(),
                    repository.salaryOfMonths(session, YearMonth.from(from), YearMonth.from(to)),
                    repository.writeOffLoss(session, from, to));

            // Party balances at the end of the last day: + = they owe us (পাবো), - = we owe them (দেবো)
            Map<Long, BigDecimal> partyNet = repository.partyNetUpTo(session, to);
            BigDecimal receivable = BigDecimal.ZERO;
            BigDecimal payable = BigDecimal.ZERO;
            int owing = 0;
            int owed = 0;
            for (Party p : repository.allParties(session)) {
                BigDecimal b = PartyLedgerService.signedOpening(p).add(partyNet.getOrDefault(p.getId(), BigDecimal.ZERO));
                if (b.signum() > 0) {
                    receivable = receivable.add(b);
                    owing++;
                } else if (b.signum() < 0) {
                    payable = payable.add(b.negate());
                    owed++;
                }
            }

            // Stock at the end of the last day: value per category (largest first) and products below their minimum
            Map<Long, BigDecimal> stocks = repository.stockUpTo(session, to);
            Map<Long, BigDecimal> costs = repository.averageCostUpTo(session, to);
            Map<Long, ProductCategory> categories = new LinkedHashMap<>();
            Map<Long, Integer> counts = new LinkedHashMap<>();
            Map<Long, BigDecimal> values = new LinkedHashMap<>();
            BigDecimal stockValue = BigDecimal.ZERO;
            List<LowStockRow> lowStock = new ArrayList<>();
            for (Product p : repository.allProducts(session)) {
                BigDecimal stock = stocks.getOrDefault(p.getId(), BigDecimal.ZERO);
                BigDecimal min = p.getMinStockAlert();
                if (min != null && min.signum() > 0 && stock.compareTo(min) < 0) {
                    lowStock.add(new LowStockRow(p, stock, min, min.subtract(stock)));
                }
                if (stock.signum() <= 0) {
                    continue;
                }
                BigDecimal value = stock.multiply(costs.getOrDefault(p.getId(), BigDecimal.ZERO))
                        .setScale(MONEY_DECIMALS, RoundingMode.HALF_UP);
                Long key = p.getCategory().getId();
                categories.putIfAbsent(key, p.getCategory());
                counts.merge(key, 1, Integer::sum);
                values.merge(key, value, BigDecimal::add);
                stockValue = stockValue.add(value);
            }
            List<CategoryStock> stock = new ArrayList<>();
            categories.forEach((key, c) -> stock.add(new CategoryStock(c, counts.get(key), values.get(key))));
            stock.sort(Comparator.comparing(CategoryStock::value).reversed());
            lowStock.sort(Comparator.comparing(LowStockRow::shortfall).reversed());

            // Account balances at the end of the last day
            Map<Long, BigDecimal> accountNet = repository.accountNetUpTo(session, to);
            List<AccountBalance> accounts = new ArrayList<>();
            BigDecimal accountsTotal = BigDecimal.ZERO;
            for (Account a : repository.allAccounts(session)) {
                BigDecimal b = a.getOpeningBalance().add(accountNet.getOrDefault(a.getId(), BigDecimal.ZERO));
                accounts.add(new AccountBalance(a, b));
                accountsTotal = accountsTotal.add(b);
            }

            return new Snapshot(period, receivable, owing, payable, owed, profit, stock, stockValue, lowStock,
                    accounts, accountsTotal);
        });
    }

    /** Rounded to paisa, so the profit lines shown add up and a tiny remainder never shows as a loss. */
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_DECIMALS, RoundingMode.HALF_UP);
    }
}
