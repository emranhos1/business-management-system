package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.User;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedger;
import com.eh.bms.model.PartyLedgerType;
import com.eh.bms.model.Product;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.model.SalaryProcessing;
import com.eh.bms.model.TransactionType;
import com.eh.bms.repository.DashboardRepository;
import com.eh.bms.repository.ReportRepository;
import com.eh.bms.service.ReportTable.Column;
import com.eh.bms.service.ReportTable.Kind;
import com.eh.bms.service.SalaryService.MonthSheet;
import com.eh.bms.service.SalaryService.SheetRow;
import com.eh.bms.service.StockReportService.Valuation;
import com.eh.bms.service.StockReportService.ValueRow;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.TransactionHelper;
import com.eh.bms.util.UnitUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reports of Stage 16 (16.2-16.12), each built as a {@link ReportTable} that the Reports page shows and
 * {@code ExcelExporter} saves. Everything is read-only. Money rules are the dashboard's: a party balance is
 * "+ they owe us (পাবো), - we owe them (দেবো)"; profit = sales (less returns) - cost of those goods - expenses -
 * the worked-out salary of the months touched (whole month, paid or not).
 */
public class ReportService {

    /** How a summary groups its rows. */
    public enum Period {
        DAILY,
        MONTHLY,
        YEARLY
    }

    /** Stand-ins for an empty date filter ("all time"). */
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);
    private static final int MONEY_DECIMALS = 2;

    private final ReportRepository repository = new ReportRepository();
    private final DashboardRepository dashboard = new DashboardRepository();
    private final StockLedgerService stockLedger = new StockLedgerService();
    private final StockReportService stockReport = new StockReportService();
    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final SalaryService salaryService = new SalaryService();

    // ------------------------------------------------------------ 16.2 দৈনিক হিসাব

    /**
     * Daily cash summary: one row for every day of {@code from}..{@code to} with the money of all accounts at the
     * start, what came in, what went out (transfers between accounts left out), the money at the end, and the
     * day's sales, purchases and expenses.
     */
    public ReportTable dailyCash(LocalDate from, LocalDate to) {
        return TransactionHelper.inTransaction(session -> {
            BigDecimal balance = BigDecimal.ZERO;
            for (Account a : dashboard.allAccounts(session)) {
                balance = balance.add(a.getOpeningBalance());
            }
            for (BigDecimal net : dashboard.accountNetUpTo(session, from.minusDays(1)).values()) {
                balance = balance.add(net);
            }
            Map<LocalDate, BigDecimal> in = repository.moneyByDate(session, from, to, AccountTransactionType.CREDIT);
            Map<LocalDate, BigDecimal> out = repository.moneyByDate(session, from, to, AccountTransactionType.DEBIT);
            Map<LocalDate, BigDecimal> sales = totalsByDate(repository.billsByDate(session, TransactionType.SALE, from, to));
            Map<LocalDate, BigDecimal> purchases = totalsByDate(
                    repository.billsByDate(session, TransactionType.PURCHASE, from, to));
            Map<LocalDate, BigDecimal> expenses = new TreeMap<>();
            repository.expensesByDate(session, from, to).forEach(r -> expenses.put((LocalDate) r[0], (BigDecimal) r[2]));

            List<List<Object>> rows = new ArrayList<>();
            BigDecimal totalIn = BigDecimal.ZERO;
            BigDecimal totalOut = BigDecimal.ZERO;
            BigDecimal totalSales = BigDecimal.ZERO;
            BigDecimal totalPurchases = BigDecimal.ZERO;
            BigDecimal totalExpenses = BigDecimal.ZERO;
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                BigDecimal dayIn = in.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal dayOut = out.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal daySales = sales.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal dayPurchases = purchases.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal dayExpenses = expenses.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal closing = balance.add(dayIn).subtract(dayOut);
                rows.add(row(day, balance, dayIn, dayOut, closing, daySales, dayPurchases, dayExpenses));
                balance = closing;
                totalIn = totalIn.add(dayIn);
                totalOut = totalOut.add(dayOut);
                totalSales = totalSales.add(daySales);
                totalPurchases = totalPurchases.add(dayPurchases);
                totalExpenses = totalExpenses.add(dayExpenses);
            }
            return new ReportTable(I18n.get("report.daily_cash"), range(from, to),
                    List.of(col("label.date", Kind.DATE), col("report.col.opening", Kind.MONEY),
                            col("report.col.money_in", Kind.MONEY), col("report.col.money_out", Kind.MONEY),
                            col("report.col.closing", Kind.MONEY), col("report.col.sales", Kind.MONEY),
                            col("report.col.purchases", Kind.MONEY), col("report.col.expenses", Kind.MONEY)),
                    rows, row(I18n.get("label.total"), null, totalIn, totalOut, null, totalSales, totalPurchases,
                    totalExpenses));
        });
    }

    // ------------------------------------------------------------ 16.3 Stock position, 16.11 Stock valuation

    /** Every product with its stock now and whether it is low or finished; {@code categoryId} null = all. */
    public ReportTable stockPosition(Long categoryId) {
        List<Product> products = TransactionHelper.inTransaction(session -> dashboard.allProducts(session).stream()
                .filter(p -> categoryId == null || categoryId.equals(p.getCategory().getId()))
                .toList());
        Map<Long, BigDecimal> stocks = stockLedger.currentStocks(products.stream().map(Product::getId).toList());
        List<List<Object>> rows = new ArrayList<>();
        for (Product p : products) {
            BigDecimal stock = stocks.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal min = p.getMinStockAlert();
            boolean hasMin = min != null && min.signum() > 0;
            String status = stock.signum() <= 0 ? "report.stock.out"
                    : hasMin && stock.compareTo(min) < 0 ? "report.stock.low" : "report.stock.ok";
            rows.add(row(UnitUtil.productName(p), categoryName(p.getCategory()), UnitUtil.quantityText(stock, p),
                    hasMin ? UnitUtil.quantityText(min, p) : "—", I18n.get(status)));
        }
        return new ReportTable(I18n.get("report.stock_position"), "", List.of(col("report.col.product", Kind.TEXT),
                col("report.col.category", Kind.TEXT), col("report.col.stock", Kind.TEXT),
                col("report.col.min_stock", Kind.TEXT), col("report.col.status", Kind.TEXT)), rows, null);
    }

    /** Each product in stock with stock x average cost, and the total value; {@code categoryId} null = all. */
    public ReportTable stockValuation(Long categoryId) {
        Valuation valuation = stockReport.valuation(categoryId);
        List<List<Object>> rows = new ArrayList<>();
        for (ValueRow r : valuation.rows()) {
            rows.add(row(UnitUtil.productName(r.product()), categoryName(r.product().getCategory()),
                    UnitUtil.quantityText(r.stock(), r.product()), r.averageCost(), r.value()));
        }
        return new ReportTable(I18n.get("report.stock_valuation"), "", List.of(col("report.col.product", Kind.TEXT),
                col("report.col.category", Kind.TEXT), col("report.col.stock", Kind.TEXT),
                col("report.col.avg_cost", Kind.MONEY), col("report.col.value", Kind.MONEY)), rows,
                row(I18n.get("label.total"), null, null, null, valuation.total()));
    }

    // ------------------------------------------------------------ 16.4 Party ledger

    /**
     * One party's ledger for the dates (null = no limit): the balance before them, every entry with the balance
     * after it, and the end balance. {@code party} null = every party with something in the dates: balance before,
     * given, received, balance after.
     */
    public ReportTable partyLedger(Party party, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? EARLIEST : from;
        LocalDate end = to == null ? LATEST : to;
        String subtitle = from == null && to == null ? I18n.get("report.all_time") : range(from, to);
        return party == null ? allPartyLedgers(start, end, subtitle) : oneLedger(party, start, end, subtitle);
    }

    private ReportTable oneLedger(Party party, LocalDate start, LocalDate end, String subtitle) {
        List<PartyLedger> entries = TransactionHelper.inTransaction(session -> repository.ledgerOf(session, party.getId()));
        BigDecimal balance = PartyLedgerService.signedOpening(party);
        for (PartyLedger l : entries) {
            if (l.getDate().isBefore(start)) {
                balance = balance.add(l.getDebit()).subtract(l.getCredit());
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(null, I18n.get("report.party.before"), null, null, PartyText.balance(balance)));
        BigDecimal gave = BigDecimal.ZERO;
        BigDecimal got = BigDecimal.ZERO;
        for (PartyLedger l : entries) {
            if (l.getDate().isBefore(start) || l.getDate().isAfter(end)) {
                continue;
            }
            balance = balance.add(l.getDebit()).subtract(l.getCredit());
            gave = gave.add(l.getDebit());
            got = got.add(l.getCredit());
            rows.add(row(l.getDate(), ledgerText(l), l.getDebit().signum() > 0 ? l.getDebit() : null,
                    l.getCredit().signum() > 0 ? l.getCredit() : null, PartyText.balance(balance)));
        }
        return new ReportTable(I18n.get("report.party_ledger") + ": " + PartyText.nameWithPhone(party), subtitle,
                List.of(col("label.date", Kind.DATE), col("report.col.details", Kind.TEXT),
                        col("party.ledger.col.gave", Kind.MONEY), col("party.ledger.col.got", Kind.MONEY),
                        col("party.ledger.col.balance", Kind.TEXT)), rows,
                row(null, I18n.get("label.total"), gave, got, PartyText.balance(balance)));
    }

    /**
     * The summary of every party with something in the dates; the Excel file also gets (more sheets, not shown on
     * the page) one ledger sheet per party listed, in the same order.
     */
    private ReportTable allPartyLedgers(LocalDate start, LocalDate end, String subtitle) {
        List<Party> listed = new ArrayList<>();
        ReportTable summary = TransactionHelper.inTransaction(session -> {
            Map<Long, BigDecimal> before = dashboard.partyNetUpTo(session, start.minusDays(1));
            Map<Long, BigDecimal> after = dashboard.partyNetUpTo(session, end);
            Map<Long, BigDecimal[]> moved = repository.partyTotalsBetween(session, start, end);
            List<List<Object>> rows = new ArrayList<>();
            BigDecimal totalBefore = BigDecimal.ZERO;
            BigDecimal totalGave = BigDecimal.ZERO;
            BigDecimal totalGot = BigDecimal.ZERO;
            BigDecimal totalAfter = BigDecimal.ZERO;
            List<Party> parties = new ArrayList<>(dashboard.allParties(session));
            parties.sort(Comparator.comparing((Party p) -> PartyText.name(p), String.CASE_INSENSITIVE_ORDER));
            for (Party p : parties) {
                BigDecimal opening = PartyLedgerService.signedOpening(p);
                BigDecimal b = opening.add(before.getOrDefault(p.getId(), BigDecimal.ZERO));
                BigDecimal a = opening.add(after.getOrDefault(p.getId(), BigDecimal.ZERO));
                BigDecimal[] m = moved.getOrDefault(p.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                if (b.signum() == 0 && a.signum() == 0 && m[0].signum() == 0 && m[1].signum() == 0) {
                    continue; // nothing to report for this party
                }
                listed.add(p);
                rows.add(row(PartyText.name(p), p.getPhone(), PartyText.type(p.getType()), PartyText.balance(b),
                        m[0], m[1], PartyText.balance(a)));
                totalBefore = totalBefore.add(b);
                totalGave = totalGave.add(m[0]);
                totalGot = totalGot.add(m[1]);
                totalAfter = totalAfter.add(a);
            }
            return new ReportTable(I18n.get("report.party_ledger"), subtitle,
                    List.of(col("report.col.party", Kind.TEXT), col("report.col.mobile", Kind.TEXT),
                            col("report.col.type", Kind.TEXT), col("report.col.before", Kind.TEXT),
                            col("party.ledger.col.gave", Kind.MONEY), col("party.ledger.col.got", Kind.MONEY),
                            col("party.ledger.col.balance", Kind.TEXT)), rows,
                    row(I18n.get("label.total"), null, null, PartyText.balance(totalBefore), totalGave, totalGot,
                            PartyText.balance(totalAfter)));
        });
        List<ReportTable> sheets = new ArrayList<>();
        for (Party p : listed) {
            sheets.add(oneLedger(p, start, end, subtitle).named(PartyText.name(p)));
        }
        return summary.withSheets(sheets);
    }

    /** "Sold goods", "Paid money" ... with the entry's note; a purchase return reads as goods sent back. */
    private static String ledgerText(PartyLedger l) {
        String key = l.getType() == PartyLedgerType.RETURN && l.getDebit().signum() > 0
                ? "party.ledger.type.RETURN_OUT" : "party.ledger.type." + l.getType().name();
        String type = I18n.get(key);
        return l.getNote() == null || l.getNote().isBlank() ? type : type + " — " + l.getNote();
    }

    // ------------------------------------------------------------ 16.5 / 16.6 / 16.7 Summaries

    /** Purchases per day, month or year of the dates: how many, the bills, returned, paid and still due. */
    public ReportTable purchaseSummary(Period period, LocalDate from, LocalDate to) {
        return billSummary(TransactionType.PURCHASE, period, from, to, "report.purchase_summary",
                "report.col.purchase_count", "report.col.purchase_total", "report.col.paid_out");
    }

    /** Sales per day, month or year of the dates: how many, the bills, returned, received and still due. */
    public ReportTable salesSummary(Period period, LocalDate from, LocalDate to) {
        return billSummary(TransactionType.SALE, period, from, to, "report.sales_summary",
                "report.col.sale_count", "report.col.sale_total", "report.col.received");
    }

    private ReportTable billSummary(TransactionType type, Period period, LocalDate from, LocalDate to,
                                    String titleKey, String countKey, String totalKey, String paidKey) {
        Map<LocalDate, BigDecimal[]> groups = new TreeMap<>();
        TransactionHelper.inTransaction(session -> repository.billsByDate(session, type, from, to)).forEach(r -> {
            BigDecimal[] sums = groups.computeIfAbsent(periodStart((LocalDate) r[0], period), d -> zeros(5));
            sums[0] = sums[0].add(BigDecimal.valueOf((Long) r[1]));
            for (int i = 1; i < 5; i++) {
                sums[i] = sums[i].add((BigDecimal) r[i + 1]);
            }
        });
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] total = zeros(5);
        groups.forEach((start, s) -> {
            rows.add(row(periodLabel(start, period), s[0], s[1], s[2], s[1].subtract(s[2]), s[3], s[4]));
            for (int i = 0; i < 5; i++) {
                total[i] = total[i].add(s[i]);
            }
        });
        return new ReportTable(I18n.get(titleKey), range(from, to) + " · " + I18n.get("report.period." + period.name()),
                List.of(col("report.col.period", Kind.TEXT), col(countKey, Kind.NUMBER), col(totalKey, Kind.MONEY),
                        col("report.col.returned", Kind.MONEY), col("report.col.net", Kind.MONEY),
                        col(paidKey, Kind.MONEY), col("report.col.due", Kind.MONEY)), rows,
                row(I18n.get("label.total"), total[0], total[1], total[2], total[1].subtract(total[2]), total[3],
                        total[4]));
    }

    /** Expenses per day, month or year of the dates: how many and how much. */
    public ReportTable expenseSummary(Period period, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal[]> groups = new TreeMap<>();
        TransactionHelper.inTransaction(session -> repository.expensesByDate(session, from, to)).forEach(r -> {
            BigDecimal[] sums = groups.computeIfAbsent(periodStart((LocalDate) r[0], period), d -> zeros(2));
            sums[0] = sums[0].add(BigDecimal.valueOf((Long) r[1]));
            sums[1] = sums[1].add((BigDecimal) r[2]);
        });
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal count = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal[]> e : groups.entrySet()) {
            rows.add(row(periodLabel(e.getKey(), period), e.getValue()[0], e.getValue()[1]));
            count = count.add(e.getValue()[0]);
            amount = amount.add(e.getValue()[1]);
        }
        return new ReportTable(I18n.get("report.expense_summary"),
                range(from, to) + " · " + I18n.get("report.period." + period.name()),
                List.of(col("report.col.period", Kind.TEXT), col("report.col.expense_count", Kind.NUMBER),
                        col("report.col.expense_total", Kind.MONEY)), rows, row(I18n.get("label.total"), count, amount));
    }

    // ------------------------------------------------------------ 16.8 Due collection, 16.9 Payable

    /** Parties who owe us money (পাবো), the biggest first. */
    public ReportTable dueCollection() {
        return openBalances(true);
    }

    /** Parties we owe money to (দেবো), the biggest first. */
    public ReportTable payable() {
        return openBalances(false);
    }

    private ReportTable openBalances(boolean receivable) {
        List<Party> parties = TransactionHelper.inTransaction(dashboard::allParties);
        Map<Long, BigDecimal> balances = partyLedger.balances(parties);
        List<Party> shown = parties.stream()
                .filter(p -> receivable ? balances.get(p.getId()).signum() > 0 : balances.get(p.getId()).signum() < 0)
                .sorted(Comparator.comparing((Party p) -> balances.get(p.getId()).abs()).reversed()
                        .thenComparing(p -> PartyText.name(p), String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Party p : shown) {
            BigDecimal amount = balances.get(p.getId()).abs();
            rows.add(row(PartyText.name(p), PartyText.type(p.getType()), p.getPhone(),
                    p.getAddress() == null ? "" : p.getAddress(), amount));
            total = total.add(amount);
        }
        return new ReportTable(I18n.get(receivable ? "report.due_collection" : "report.payable"),
                I18n.get("report.as_of", DateUtil.format(LocalDate.now())),
                List.of(col("report.col.party", Kind.TEXT), col("report.col.type", Kind.TEXT),
                        col("report.col.mobile", Kind.TEXT), col("report.col.address", Kind.TEXT),
                        col(receivable ? "report.col.to_receive" : "report.col.to_pay", Kind.MONEY)), rows,
                row(I18n.get("label.total"), null, null, null, total));
    }

    // ------------------------------------------------------------ 16.10 Salary

    /** Every user's salary for the month: gross, deductions, bonus, net, advance carried in, paid, due. */
    public ReportTable salary(YearMonth month) {
        MonthSheet sheet = salaryService.monthSheet(month);
        List<List<Object>> rows = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            User u = r.user();
            SalaryProcessing s = r.sheet();
            rows.add(row(u.getName(), I18n.get("role." + u.getRole().name()),
                    u.getSalaryType() == null ? "" : I18n.get("salary.type." + u.getSalaryType().name()),
                    s == null ? null : s.getGrossSalary(), s == null ? null : s.getDeductions(),
                    s == null ? null : s.getBonus(), s == null ? null : s.getNetSalary(), r.carryIn(), r.paid(),
                    r.due(), I18n.get(s == null ? "report.salary.not_done" : "report.salary.done")));
        }
        return new ReportTable(I18n.get("report.salary"), monthName(month),
                List.of(col("salary.col.employee", Kind.TEXT), col("report.col.designation", Kind.TEXT),
                        col("salary.col.type", Kind.TEXT), col("report.col.gross", Kind.MONEY),
                        col("report.col.deductions", Kind.MONEY), col("report.col.bonus", Kind.MONEY),
                        col("salary.col.net", Kind.MONEY), col("salary.col.carry", Kind.MONEY),
                        col("salary.col.paid", Kind.MONEY), col("salary.col.due", Kind.MONEY),
                        col("report.col.status", Kind.TEXT)), rows,
                row(I18n.get("label.total"), null, null, sheet.totalGross(), sheet.totalDeductions(),
                        sheet.totalBonus(), sheet.totalNet(), sheet.totalCarry(), sheet.totalPaid(), sheet.totalDue(),
                        null));
    }

    // ------------------------------------------------------------ 16.12 Profit & Loss

    /**
     * Profit and loss per month or year of the dates (the dashboard's rules). The salary of a period is that of
     * every month it touches, whole months. {@code period} must be monthly or yearly.
     */
    public ReportTable profitLoss(Period period, LocalDate from, LocalDate to) {
        if (period == Period.DAILY) {
            throw new IllegalArgumentException("Profit and loss is per month or year");
        }
        return TransactionHelper.inTransaction(session -> {
            List<List<Object>> rows = new ArrayList<>();
            BigDecimal[] total = zeros(5);
            for (LocalDate start = periodStart(from, period); !start.isAfter(to); start = nextPeriod(start, period)) {
                LocalDate first = start.isBefore(from) ? from : start;
                LocalDate periodEnd = nextPeriod(start, period).minusDays(1);
                LocalDate last = periodEnd.isAfter(to) ? to : periodEnd;
                BigDecimal sales = money(dashboard.netSales(session, first, last));
                BigDecimal cost = money(dashboard.costOfSales(session, first, last));
                BigDecimal expenses = dashboard.expenses(session, first, last);
                BigDecimal salary = dashboard.salaryOfMonths(session, YearMonth.from(first), YearMonth.from(last));
                BigDecimal net = sales.subtract(cost).subtract(expenses).subtract(salary);
                rows.add(row(periodLabel(start, period), sales, cost, sales.subtract(cost), expenses, salary, net,
                        I18n.get(net.signum() < 0 ? "report.loss" : "report.profit")));
                BigDecimal[] parts = {sales, cost, expenses, salary};
                for (int i = 0; i < parts.length; i++) {
                    total[i] = total[i].add(parts[i]);
                }
            }
            BigDecimal net = total[0].subtract(total[1]).subtract(total[2]).subtract(total[3]);
            return new ReportTable(I18n.get("report.profit_loss"),
                    range(from, to) + " · " + I18n.get("report.period." + period.name()) + " · "
                            + I18n.get("report.salary_note"),
                    List.of(col("report.col.period", Kind.TEXT), col("report.col.sales_net", Kind.MONEY),
                            col("report.col.cost", Kind.MONEY), col("report.col.gross_profit", Kind.MONEY),
                            col("report.col.expenses", Kind.MONEY), col("report.col.salary", Kind.MONEY),
                            col("report.col.net_profit", Kind.MONEY), col("report.col.result", Kind.TEXT)), rows,
                    row(I18n.get("label.total"), total[0], total[1], total[0].subtract(total[1]), total[2], total[3],
                            net, I18n.get(net.signum() < 0 ? "report.loss" : "report.profit")));
        });
    }

    // ------------------------------------------------------------ helpers

    /** A row of cells; {@code Arrays.asList} because cells may be null (blank). */
    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private static Column col(String headingKey, Kind kind) {
        return new Column(I18n.get(headingKey), kind);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    /** Rounded to paisa, so the lines shown add up. */
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_DECIMALS, RoundingMode.HALF_UP);
    }

    /** Bill total of each day from {@link ReportRepository#billsByDate} rows. */
    private static Map<LocalDate, BigDecimal> totalsByDate(List<Object[]> rows) {
        Map<LocalDate, BigDecimal> result = new TreeMap<>();
        rows.forEach(r -> result.put((LocalDate) r[0], (BigDecimal) r[2]));
        return result;
    }

    /** "১৪-১০-২০২৬ – ১৯-১০-২০২৬"; a missing end shows as "…". */
    private static String range(LocalDate from, LocalDate to) {
        return (from == null ? "…" : DateUtil.format(from)) + " – " + (to == null ? "…" : DateUtil.format(to));
    }

    private static String categoryName(ProductCategory c) {
        return c == null ? "—" : I18n.pick(c.getName(), c.getNameEn());
    }

    private static LocalDate periodStart(LocalDate date, Period period) {
        return switch (period) {
            case DAILY -> date;
            case MONTHLY -> date.withDayOfMonth(1);
            case YEARLY -> date.withDayOfYear(1);
        };
    }

    private static LocalDate nextPeriod(LocalDate start, Period period) {
        return switch (period) {
            case DAILY -> start.plusDays(1);
            case MONTHLY -> start.plusMonths(1);
            case YEARLY -> start.plusYears(1);
        };
    }

    private static String periodLabel(LocalDate start, Period period) {
        return switch (period) {
            case DAILY -> DateUtil.format(start);
            case MONTHLY -> monthName(YearMonth.from(start));
            case YEARLY -> I18n.digits(String.valueOf(start.getYear()));
        };
    }

    private static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, I18n.getLocale()) + " "
                + I18n.digits(String.valueOf(month.getYear()));
    }
}
