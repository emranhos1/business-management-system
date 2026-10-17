package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedgerType;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.model.PaymentMethod;
import com.eh.bms.model.Product;
import com.eh.bms.model.StockLedgerType;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.model.TransactionPayment;
import com.eh.bms.model.TransactionStatus;
import com.eh.bms.model.TransactionType;
import com.eh.bms.model.Unit;
import com.eh.bms.repository.TransactionRepository;
import com.eh.bms.repository.TransactionRepository.Filter;
import com.eh.bms.service.SaleService.LineData;
import com.eh.bms.service.SaleService.PaymentData;
import com.eh.bms.service.SaleService.SaleDetails;
import com.eh.bms.service.SaleService.Totals;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Purchases (Stage 12). Confirming a purchase (12.2) writes, in one transaction: the purchase and its lines,
 * stock in for each line at its cost (the average cost changes), the party ledger (goods got = credit, money
 * paid = debit) and the money out of each account. Later payments and returns (12.4) work the same way.
 * Any failure rolls everything back.
 * <p>
 * Lock order (no deadlocks): party, then the purchase row, then products (by id), then accounts (by id).
 * Money: same sums as a sale without VAT (user decision); the bill discount is shared out over the lines, so
 * each line's stock cost is what was really paid for it.
 */
public class PurchaseService {

    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 3;
    private static final int COST_SCALE = 4;

    /**
     * The whole purchase. {@code partyId} null = cash purchase without a supplier ({@code sellerName} /
     * {@code sellerPhone} optional, kept in the note; it must be paid in full). {@code supplierMemoNo} is the
     * supplier's own bill number (optional).
     */
    public record PurchaseData(Long partyId, String sellerName, String sellerPhone, String supplierMemoNo,
                               LocalDate date, List<LineData> lines, BigDecimal billDiscount,
                               List<PaymentData> payments, String note) {
    }

    public enum Status {
        DONE, INSUFFICIENT_STOCK, INSUFFICIENT_BALANCE, DUE_WITHOUT_PARTY, OVERPAID, INACTIVE_PARTY, TOO_MUCH
    }

    /**
     * {@code memoNo} is set for DONE; {@code productId}/{@code available} for INSUFFICIENT_STOCK (stock in main
     * unit, on a return); {@code accountId}/{@code available} for INSUFFICIENT_BALANCE; {@code available} for
     * TOO_MUCH (the most allowed) and for a DONE return (value of the goods sent back).
     */
    public record Result(Status status, Long memoNo, Long productId, Long accountId, BigDecimal available) {
        static Result done(Long memoNo) {
            return new Result(Status.DONE, memoNo, null, null, null);
        }

        static Result of(Status status) {
            return new Result(status, null, null, null, null);
        }
    }

    private final TransactionRepository repository = new TransactionRepository(TransactionType.PURCHASE);
    private final StockLedgerService stockLedger = new StockLedgerService();
    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

    /** Same sums the screen shows and the purchase saves (no VAT). */
    public static Totals totals(List<LineData> lines, BigDecimal billDiscount) {
        return SaleService.totals(lines, billDiscount, BigDecimal.ZERO);
    }

    /**
     * Price of one main unit to suggest on a new purchase: the price on the newest purchase of the product
     * (before discounts, so a discount typed again is not taken twice), else the average cost (e.g. opening
     * stock); null when there is neither.
     */
    public BigDecimal suggestedCost(long productId) {
        return TransactionHelper.inTransaction(session -> repository.lastItem(session, productId)
                .map(i -> i.getUnitPrice().multiply(i.getQuantity())
                        .divide(i.getMainQuantity(), COST_SCALE, RoundingMode.HALF_UP))
                .orElseGet(() -> {
                    BigDecimal avg = stockLedger.averageCost(session, productId);
                    return avg.signum() > 0 ? avg : null;
                }));
    }

    public PageResult<BusinessTransaction> page(Filter filter, int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, filter, offset, limit), repository.count(session, filter)));
    }

    public SaleDetails details(long purchaseId) {
        return TransactionHelper.inTransaction(session -> {
            BusinessTransaction purchase = repository.find(session, purchaseId)
                    .orElseThrow(() -> new IllegalStateException("Purchase not found: " + purchaseId));
            return new SaleDetails(purchase, repository.items(session, purchaseId),
                    repository.payments(session, purchaseId));
        });
    }

    /** Confirms a purchase (12.2). Nothing is saved unless every step succeeds. */
    public Result confirm(PurchaseData data) {
        if (data.lines().isEmpty()) {
            throw new IllegalArgumentException("A purchase needs at least one line");
        }
        for (LineData l : data.lines()) {
            if (l.quantity().signum() <= 0 || l.unitPrice().signum() < 0 || l.discountPerUnit().signum() < 0
                    || l.discountPerUnit().compareTo(l.unitPrice()) > 0) {
                throw new IllegalArgumentException("Bad purchase line for product " + l.productId());
            }
        }
        for (PaymentData p : data.payments()) {
            if (p.amount().signum() < 0) {
                throw new IllegalArgumentException("Payment must not be below 0");
            }
        }
        try {
            return TransactionHelper.inTransaction(session -> {
                Party party = null;
                if (data.partyId() != null) {
                    party = partyLedger.lock(session, data.partyId());
                    if (party.getStatus() == PartyStatus.INACTIVE) {
                        return Result.of(Status.INACTIVE_PARTY);
                    }
                }
                Totals totals = totals(data.lines(), data.billDiscount());
                BigDecimal paid = BigDecimal.ZERO;
                for (PaymentData p : data.payments()) {
                    paid = paid.add(p.amount());
                }
                // A cash purchase without a supplier cannot pay more than the bill; a supplier's extra goes to
                // its ledger (lowers what we owed before, or is kept as advance)
                if (paid.compareTo(totals.grandTotal()) > 0 && party == null) {
                    return Result.of(Status.OVERPAID);
                }
                BigDecimal purchasePaid = paid.min(totals.grandTotal());
                BigDecimal extra = paid.subtract(purchasePaid);
                BigDecimal due = totals.grandTotal().subtract(purchasePaid);
                if (due.signum() > 0 && party == null) {
                    return Result.of(Status.DUE_WITHOUT_PARTY);
                }

                long memoNo = repository.nextMemoNo(session);
                String memoNote = "#" + memoNo;
                BusinessTransaction purchase = new BusinessTransaction();
                purchase.setType(TransactionType.PURCHASE);
                purchase.setParty(party);
                purchase.setDate(data.date());
                purchase.setSubtotal(totals.subtotal());
                purchase.setDiscount(totals.discount());
                purchase.setTaxAmount(totals.vat());
                purchase.setGrandTotal(totals.grandTotal());
                purchase.setPaidAmount(purchasePaid);
                purchase.setDueAmount(due);
                purchase.setStatus(TransactionStatus.CONFIRMED);
                purchase.setMemoNo(memoNo);
                // Bangla digits saved as 0-9, so a search finds it either way
                purchase.setSupplierMemoNo(blankToNull(data.supplierMemoNo() == null ? null
                        : InputFilters.toAsciiDigits(data.supplierMemoNo()), 50));
                purchase.setNote(note(data));
                repository.save(session, purchase);

                // Stock in, product by product in id order (lock order), each at what it really cost
                BigDecimal factor = billFactor(purchase);
                List<LineData> lines = new ArrayList<>(data.lines());
                lines.sort(Comparator.comparingLong(LineData::productId));
                for (LineData l : lines) {
                    Product product = session.find(Product.class, l.productId());
                    Unit unit = SaleService.unitOf(product, l.unitId());
                    BigDecimal mainQty = SaleService.toMain(product, unit, l.quantity());
                    if (mainQty.signum() <= 0) {
                        throw new IllegalArgumentException("Quantity too small for product " + l.productId());
                    }
                    BigDecimal cost = l.total().multiply(factor).divide(mainQty, COST_SCALE, RoundingMode.HALF_UP);
                    stockLedger.post(session, l.productId(), data.date(), StockLedgerType.PURCHASE, mainQty, cost,
                            null, purchase.getId(), memoNote);
                    TransactionItem item = new TransactionItem();
                    item.setTransaction(purchase);
                    item.setProduct(product);
                    item.setQuantity(l.quantity());
                    item.setUnitRef(unit);
                    item.setUnit(unit.getNameEn() == null ? unit.getNameBn() : unit.getNameEn());
                    item.setMainQuantity(mainQty);
                    item.setUnitPrice(l.unitPrice());
                    item.setDiscountPerUnit(l.discountPerUnit());
                    item.setTotalPrice(l.total());
                    repository.save(session, item);
                }

                // What we owe the supplier for the goods, then what we paid
                // A free purchase (total 0) moves only stock; the ledger takes no 0 row
                if (party != null && totals.grandTotal().signum() > 0) {
                    partyLedger.post(session, party.getId(), data.date(), PartyLedgerType.PURCHASE,
                            BigDecimal.ZERO, totals.grandTotal(), purchase.getId(), memoNote);
                }
                // One payment row per account, even if the same account was sent twice; accounts in id order
                Map<Long, BigDecimal> byAccount = new TreeMap<>();
                for (PaymentData p : data.payments()) {
                    if (p.amount().signum() > 0) {
                        byAccount.merge(p.accountId(), p.amount(), BigDecimal::add);
                    }
                }
                PaymentMethod mainMethod = null;
                BigDecimal toPurchase = purchasePaid;
                for (Map.Entry<Long, BigDecimal> e : byAccount.entrySet()) {
                    long accountId = e.getKey();
                    BigDecimal amount = e.getValue();
                    Account account = accountLedger.lock(session, accountId);
                    // Money going out on a past date must be there from that day on
                    BigDecimal available = accountLedger.availableOn(session, accountId, data.date());
                    if (available.compareTo(amount) < 0) {
                        throw new Abort(new Result(Status.INSUFFICIENT_BALANCE, null, null, accountId,
                                available.max(BigDecimal.ZERO)));
                    }
                    // The bill is paid first; the rest is a plain payment to the supplier's ledger
                    BigDecimal part = amount.min(toPurchase);
                    if (part.signum() > 0) {
                        pay(session, purchase, party, account, part, data.date(), memoNote);
                        toPurchase = toPurchase.subtract(part);
                        if (mainMethod == null) {
                            mainMethod = SaleService.methodOf(account);
                        }
                    }
                    BigDecimal rest = amount.subtract(part);
                    if (rest.signum() > 0) {
                        payForLedger(session, purchase, party, accountId, rest, data.date(), memoNote);
                    }
                }
                purchase.setPaymentMethod(mainMethod);

                auditService.created(session, AppModule.PURCHASE, purchase.getId(), "purchase memo=" + memoNo
                        + " party=" + (party == null ? "cash" : party.getName()) + " supplierMemo="
                        + purchase.getSupplierMemoNo() + " total=" + totals.grandTotal() + " paid=" + purchasePaid
                        + " extra=" + extra + " due=" + due + " lines=" + data.lines().size());
                return Result.done(memoNo);
            });
        } catch (Abort a) {
            return a.result;
        }
    }

    /** A later payment on a purchase's due: money out of an account, the supplier's ledger and the due. */
    public Result payDue(long purchaseId, long accountId, BigDecimal amount, LocalDate date, String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        return TransactionHelper.inTransaction(session -> {
            BusinessTransaction purchase = lockPurchase(session, purchaseId);
            if (isInactive(purchase)) {
                return Result.of(Status.INACTIVE_PARTY);
            }
            if (date.isBefore(purchase.getDate())) {
                throw new IllegalArgumentException("Payment date is before the purchase date");
            }
            if (purchase.getDueAmount().compareTo(amount) < 0) {
                return new Result(Status.TOO_MUCH, null, null, null, purchase.getDueAmount());
            }
            Account account = accountLedger.lock(session, accountId);
            BigDecimal available = accountLedger.availableOn(session, accountId, date);
            if (available.compareTo(amount) < 0) {
                return new Result(Status.INSUFFICIENT_BALANCE, null, null, accountId, available.max(BigDecimal.ZERO));
            }
            String memoNote = "#" + purchase.getMemoNo() + (note == null || note.isBlank() ? "" : " — " + note.trim());
            pay(session, purchase, purchase.getParty(), account, amount, date, memoNote);
            purchase.setPaidAmount(purchase.getPaidAmount().add(amount));
            purchase.setDueAmount(purchase.getDueAmount().subtract(amount));
            auditService.created(session, AppModule.PURCHASE, purchase.getId(),
                    "payment memo=" + purchase.getMemoNo() + " amount=" + amount + " date=" + date);
            return Result.done(purchase.getMemoNo());
        });
    }

    /**
     * A purchase return (12.4): {@code quantities} by line id, in each line's unit. Stock goes out at what it
     * cost; the value of the goods (with the bill discount shared out) is debited to the supplier;
     * {@code refund} of it may come back into an account (a cash purchase without a supplier gets it all back).
     */
    public Result returnGoods(long purchaseId, Map<Long, BigDecimal> quantities, Long refundAccountId,
                              BigDecimal refund, LocalDate date, String note) {
        try {
            return TransactionHelper.inTransaction(session -> {
                BusinessTransaction purchase = lockPurchase(session, purchaseId);
                if (isInactive(purchase)) {
                    return Result.of(Status.INACTIVE_PARTY);
                }
                if (date.isBefore(purchase.getDate())) {
                    throw new IllegalArgumentException("Return date is before the purchase date");
                }
                Party party = purchase.getParty();
                List<TransactionItem> items = repository.items(session, purchaseId);
                items.sort(Comparator.comparingLong(i -> i.getProduct().getId()));
                BigDecimal factor = billFactor(purchase);
                String memoNote = "#" + purchase.getMemoNo() + (note == null || note.isBlank() ? "" : " — " + note.trim());

                BigDecimal value = BigDecimal.ZERO;
                boolean anyBack = false;
                for (TransactionItem item : items) {
                    BigDecimal qty = quantities.get(item.getId());
                    if (qty == null || qty.signum() <= 0) {
                        continue;
                    }
                    BigDecimal left = item.getQuantity().subtract(item.getReturnedQuantity());
                    if (qty.compareTo(left) > 0) {
                        throw new Abort(new Result(Status.TOO_MUCH, null, item.getProduct().getId(), null, left));
                    }
                    // Worked out on the total sent back so far, so several part returns add up to the line exactly
                    // (5 + 5 of 10 pcs bought as 0.833 dozen give 0.417 + 0.416, not 0.417 + 0.417)
                    BigDecimal mainQty = mainShare(item, item.getReturnedQuantity().add(qty))
                            .subtract(mainShare(item, item.getReturnedQuantity()));
                    // What one main unit of this line cost (same as when it came in)
                    BigDecimal cost = item.getTotalPrice().multiply(factor)
                            .divide(item.getMainQuantity(), COST_SCALE, RoundingMode.HALF_UP);
                    StockLedgerService.Result stock = stockLedger.post(session, item.getProduct().getId(), date,
                            StockLedgerType.PURCHASE_RETURN, mainQty.negate(), cost, null, purchase.getId(), memoNote);
                    if (stock.status() == StockLedgerService.Status.INSUFFICIENT_STOCK) {
                        throw new Abort(new Result(Status.INSUFFICIENT_STOCK, null, item.getProduct().getId(), null,
                                stock.available()));
                    }
                    item.setReturnedQuantity(item.getReturnedQuantity().add(qty));
                    anyBack = true;
                    value = value.add(qty.multiply(item.getUnitPrice().subtract(item.getDiscountPerUnit())));
                }
                if (!anyBack) {
                    throw new IllegalArgumentException("Nothing to return");
                }
                value = value.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                // Never more than what the purchase is still worth (rounding on the last return)
                value = value.min(purchase.getGrandTotal().subtract(purchase.getReturnedAmount())).max(BigDecimal.ZERO);

                // Stock is already written above, so a refusal must roll back (Abort), not return
                BigDecimal back = refund == null ? BigDecimal.ZERO : refund;
                // The goods first clear what we still owe; only money actually paid can come back
                BigDecimal maxRefund = SaleService.maxRefund(value, purchase.getDueAmount());
                if (back.compareTo(maxRefund) > 0) {
                    throw new Abort(new Result(Status.TOO_MUCH, null, null, null, maxRefund));
                }
                if (party == null && back.compareTo(value) != 0) {
                    // No supplier ledger: the whole value comes back in money
                    throw new Abort(new Result(Status.DUE_WITHOUT_PARTY, null, null, null, value));
                }
                // Goods worth 0 (a free line) only leave the stock
                if (party != null && value.signum() > 0) {
                    partyLedger.post(session, party.getId(), date, PartyLedgerType.RETURN,
                            value, BigDecimal.ZERO, purchase.getId(), memoNote);
                }
                if (back.signum() > 0) {
                    if (refundAccountId == null) {
                        throw new IllegalArgumentException("Refund account is required");
                    }
                    accountLedger.lock(session, refundAccountId);
                    AccountTransaction money = accountLedger.post(session, refundAccountId, date,
                            AccountTransactionType.CREDIT, back, AccountTransactionSource.PURCHASE, purchase.getId(),
                            memoNote);
                    if (party != null) {
                        partyLedger.post(session, party.getId(), date, PartyLedgerType.RECEIPT,
                                BigDecimal.ZERO, back, money.getId(), memoNote);
                    }
                }
                // What did not come back in money lowers the due (what is left over stays in the supplier's ledger)
                BigDecimal credit = value.subtract(back);
                purchase.setDueAmount(purchase.getDueAmount().subtract(credit).max(BigDecimal.ZERO));
                purchase.setReturnedAmount(purchase.getReturnedAmount().add(value));

                auditService.created(session, AppModule.PURCHASE, purchase.getId(), "return memo="
                        + purchase.getMemoNo() + " value=" + value + " refund=" + back + " date=" + date);
                return new Result(Status.DONE, purchase.getMemoNo(), null, null, value);
            });
        } catch (Abort a) {
            return a.result;
        }
    }

    /**
     * Money paid for a purchase: out of the (locked) account, a payment row on the purchase and, with a
     * supplier, a debit in its ledger (pointing at the account entry, so the ledger names the account).
     */
    private void pay(Session session, BusinessTransaction purchase, Party party, Account account, BigDecimal amount,
                     LocalDate date, String note) {
        AccountTransaction money = accountLedger.post(session, account.getId(), date, AccountTransactionType.DEBIT,
                amount, AccountTransactionSource.PURCHASE, purchase.getId(), note);
        TransactionPayment payment = new TransactionPayment();
        payment.setTransaction(purchase);
        payment.setDate(date);
        payment.setAmount(amount);
        payment.setPaymentMethod(SaleService.methodOf(account));
        payment.setAccount(account);
        payment.setNote(note);
        repository.save(session, payment);
        if (party != null) {
            partyLedger.post(session, party.getId(), date, PartyLedgerType.PAYMENT,
                    amount, BigDecimal.ZERO, money.getId(), note);
        }
    }

    /**
     * Money paid to a supplier beyond the bill (account out, party ledger debit): not a payment row of the
     * purchase, so it does not lower its due, but it is marked as purchase money (source PURCHASE, pointing at
     * the purchase), so the ledgers name it "ক্রয় মেমো #n" and not a sale's memo.
     */
    private void payForLedger(Session session, BusinessTransaction purchase, Party party, long accountId,
                              BigDecimal amount, LocalDate date, String note) {
        AccountTransaction money = accountLedger.post(session, accountId, date, AccountTransactionType.DEBIT,
                amount, AccountTransactionSource.PURCHASE, purchase.getId(), note);
        partyLedger.post(session, party.getId(), date, PartyLedgerType.PAYMENT,
                amount, BigDecimal.ZERO, money.getId(), note);
    }

    /** Locks the purchase's party (if any) and then the purchase row, in that order. */
    private BusinessTransaction lockPurchase(Session session, long purchaseId) {
        BusinessTransaction purchase = repository.find(session, purchaseId)
                .orElseThrow(() -> new IllegalStateException("Purchase not found: " + purchaseId));
        if (purchase.getParty() != null) {
            partyLedger.lock(session, purchase.getParty().getId());
        }
        session.lock(purchase, LockModeType.PESSIMISTIC_WRITE);
        session.refresh(purchase);
        return purchase;
    }

    /** An inactive party's ledger must not move (it is hidden from money screens); make it active first. */
    private static boolean isInactive(BusinessTransaction purchase) {
        return purchase.getParty() != null && purchase.getParty().getStatus() == PartyStatus.INACTIVE;
    }

    /** Main-unit quantity of {@code qty} (in the line's unit) of a line. */
    private static BigDecimal mainShare(TransactionItem item, BigDecimal qty) {
        return item.getMainQuantity().multiply(qty).divide(item.getQuantity(), QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** Grand total / subtotal: the share of the bill discount each line's money carries (1 for a 0 bill). */
    public static BigDecimal billFactor(BusinessTransaction t) {
        return t.getSubtotal().signum() == 0 ? BigDecimal.ONE
                : t.getGrandTotal().divide(t.getSubtotal(), 10, RoundingMode.HALF_UP);
    }

    /** The user's note, with a cash seller's name and mobile in front ("Name (01...) — note"). */
    private static String note(PurchaseData data) {
        String who = "";
        if (data.partyId() == null) {
            String name = data.sellerName() == null ? "" : data.sellerName().trim();
            String phone = data.sellerPhone() == null ? "" : data.sellerPhone().trim();
            who = name + (phone.isEmpty() ? "" : (name.isEmpty() ? "" : " ") + "(" + phone + ")");
        }
        String note = data.note() == null ? "" : data.note().trim();
        String all = who.isEmpty() ? note : note.isEmpty() ? who : who + " — " + note;
        return blankToNull(all, 255);
    }

    private static String blankToNull(String text, int max) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? null : t.length() > max ? t.substring(0, max) : t;
    }

    /** Stops a transaction (rolls it back) with a result for the screen. */
    private static final class Abort extends RuntimeException {
        private final Result result;

        Abort(Result result) {
            super(result.status().name(), null, false, false);
            this.result = result;
        }
    }
}
