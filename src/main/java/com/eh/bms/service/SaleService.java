package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedger;
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
import com.eh.bms.repository.BusinessProfileRepository;
import com.eh.bms.repository.TransactionRepository;
import com.eh.bms.repository.TransactionRepository.Filter;
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
import java.util.Optional;

/**
 * Sales (Stage 11). Confirming a sale (11.4) writes, in one transaction: the sale and its lines, stock out
 * for each line, the party ledger (goods given = debit, money received = credit) and the money into each
 * account. Installments (11.5) and returns (11.7) work the same way. Any failure rolls everything back.
 * <p>
 * Lock order (no deadlocks): party, then the sale row, then products (by id), then accounts (by id).
 * Money: line total = qty x (price - discount per unit); subtotal = sum of lines; VAT on (subtotal - bill
 * discount); grand total = subtotal - bill discount + VAT.
 */
public class SaleService {

    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 3;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** One line from the sale screen. {@code unitId} is the product's unit or its secondary unit. */
    public record LineData(long productId, long unitId, BigDecimal quantity, BigDecimal unitPrice,
                           BigDecimal discountPerUnit) {
        public BigDecimal total() {
            return quantity.multiply(unitPrice.subtract(discountPerUnit)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    /** Money received into one account. */
    public record PaymentData(long accountId, BigDecimal amount) {
    }

    /**
     * The whole sale. {@code partyId} null = walk-in customer ({@code walkInName}/{@code walkInPhone} optional,
     * kept in the note; no due allowed). {@code vatPercent} is the VAT % the screen used (0 = no VAT); it must
     * still be the one in Settings, so the saved total is the total the user saw.
     */
    public record SaleData(Long partyId, String walkInName, String walkInPhone, LocalDate date,
                           List<LineData> lines, BigDecimal billDiscount, BigDecimal vatPercent,
                           List<PaymentData> payments, String note) {
    }

    public record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal vat, BigDecimal grandTotal) {
    }

    public enum Status {
        DONE, INSUFFICIENT_STOCK, INSUFFICIENT_BALANCE, DUE_WITHOUT_PARTY, OVERPAID, INACTIVE_PARTY, TOO_MUCH,
        /** The VAT % in Settings changed while the sale was being typed. */
        VAT_CHANGED
    }

    /**
     * {@code memoNo} is set for DONE; {@code productId}/{@code available} for INSUFFICIENT_STOCK (stock in main
     * unit); {@code accountId}/{@code available} for INSUFFICIENT_BALANCE; {@code available} for TOO_MUCH (the
     * most allowed).
     */
    public record Result(Status status, Long memoNo, Long productId, Long accountId, BigDecimal available) {
        static Result done(Long memoNo) {
            return new Result(Status.DONE, memoNo, null, null, null);
        }

        static Result of(Status status) {
            return new Result(status, null, null, null, null);
        }
    }

    /** A sale with everything the detail and return screens show. */
    public record SaleDetails(BusinessTransaction sale, List<TransactionItem> items, List<TransactionPayment> payments) {
    }

    private final TransactionRepository repository = new TransactionRepository(TransactionType.SALE);
    private final BusinessProfileRepository profileRepository = new BusinessProfileRepository();
    private final StockLedgerService stockLedger = new StockLedgerService();
    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

    /** Same sums the screen shows and the sale saves. {@code vatPercent} 0 = no VAT. */
    public static Totals totals(List<LineData> lines, BigDecimal billDiscount, BigDecimal vatPercent) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (LineData l : lines) {
            subtotal = subtotal.add(l.total());
        }
        BigDecimal discount = billDiscount == null ? BigDecimal.ZERO : billDiscount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // The bill discount comes off the whole bill, VAT included (user decision), so it can be as big as the bill
        BigDecimal vat = vatPercent == null || vatPercent.signum() <= 0 ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : subtotal.multiply(vatPercent).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        return new Totals(subtotal, discount, vat, subtotal.add(vat).subtract(discount).max(BigDecimal.ZERO));
    }

    /** VAT % from the business profile (Settings); 0 when none. */
    public BigDecimal vatPercent() {
        return TransactionHelper.inTransaction(this::vatPercent);
    }

    private BigDecimal vatPercent(Session session) {
        BigDecimal vat = profileRepository.get(session).getVatPercentage();
        return vat == null ? BigDecimal.ZERO : vat;
    }

    public PageResult<BusinessTransaction> page(Filter filter, int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, filter, offset, limit), repository.count(session, filter)));
    }

    public SaleDetails details(long saleId) {
        return TransactionHelper.inTransaction(session -> {
            BusinessTransaction sale = repository.find(session, saleId)
                    .orElseThrow(() -> new IllegalStateException("Sale not found: " + saleId));
            return new SaleDetails(sale, repository.items(session, saleId), repository.payments(session, saleId));
        });
    }

    /**
     * Everything a printed memo shows (N6). {@code before} / {@code now}: the party's balance just before this
     * sale and today (+ the party owes us); both null for a walk-in customer.
     */
    public record Memo(SaleDetails details, BusinessProfile profile, BigDecimal before, BigDecimal now) {
    }

    public Memo memo(long saleId) {
        return TransactionHelper.inTransaction(session -> {
            BusinessTransaction sale = repository.find(session, saleId)
                    .orElseThrow(() -> new IllegalStateException("Sale not found: " + saleId));
            SaleDetails details = new SaleDetails(sale, repository.items(session, saleId), repository.payments(session, saleId));
            BigDecimal before = null;
            BigDecimal now = null;
            if (sale.getParty() != null) {
                long partyId = sale.getParty().getId();
                before = partyLedger.balanceBeforeSale(session, partyId, saleId, sale.getDate());
                now = partyLedger.balance(session, partyId);
            }
            return new Memo(details, profileRepository.get(session), before, now);
        });
    }

    /** The sale with this memo number, if any. */
    public Optional<Long> idOfMemo(long memoNo) {
        return TransactionHelper.inTransaction(session -> repository.idOfMemo(session, memoNo));
    }

    /** Confirms a sale (11.4). Nothing is saved unless every step succeeds. */
    public Result confirm(SaleData data) {
        if (data.lines().isEmpty()) {
            throw new IllegalArgumentException("A sale needs at least one line");
        }
        for (LineData l : data.lines()) {
            if (l.quantity().signum() <= 0 || l.unitPrice().signum() < 0 || l.discountPerUnit().signum() < 0
                    || l.discountPerUnit().compareTo(l.unitPrice()) > 0) {
                throw new IllegalArgumentException("Bad sale line for product " + l.productId());
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
                BigDecimal vat = data.vatPercent() == null ? BigDecimal.ZERO : data.vatPercent();
                if (vat.signum() > 0 && vat.compareTo(vatPercent(session)) != 0) {
                    return Result.of(Status.VAT_CHANGED);
                }
                Totals totals = totals(data.lines(), data.billDiscount(), vat);
                BigDecimal paid = BigDecimal.ZERO;
                for (PaymentData p : data.payments()) {
                    paid = paid.add(p.amount());
                }
                // A walk-in customer cannot pay more than the bill; a party's extra goes to its ledger
                // (lowers what it owed before, or is kept as advance)
                if (paid.compareTo(totals.grandTotal()) > 0 && party == null) {
                    return Result.of(Status.OVERPAID);
                }
                BigDecimal salePaid = paid.min(totals.grandTotal());
                BigDecimal extra = paid.subtract(salePaid);
                BigDecimal due = totals.grandTotal().subtract(salePaid);
                if (due.signum() > 0 && party == null) {
                    return Result.of(Status.DUE_WITHOUT_PARTY);
                }

                long memoNo = repository.nextMemoNo(session);
                String memoNote = "#" + memoNo;
                BusinessTransaction sale = new BusinessTransaction();
                sale.setType(TransactionType.SALE);
                sale.setParty(party);
                sale.setDate(data.date());
                sale.setSubtotal(totals.subtotal());
                sale.setDiscount(totals.discount());
                sale.setTaxAmount(totals.vat());
                sale.setGrandTotal(totals.grandTotal());
                sale.setPaidAmount(salePaid);
                sale.setDueAmount(due);
                sale.setStatus(TransactionStatus.CONFIRMED);
                sale.setMemoNo(memoNo);
                sale.setNote(note(data));
                repository.save(session, sale);

                // Stock out, product by product in id order (lock order)
                List<LineData> lines = new ArrayList<>(data.lines());
                lines.sort(Comparator.comparingLong(LineData::productId));
                for (LineData l : lines) {
                    Product product = session.find(Product.class, l.productId());
                    Unit unit = unitOf(product, l.unitId());
                    BigDecimal mainQty = toMain(product, unit, l.quantity());
                    StockLedgerService.Result stock = stockLedger.post(session, l.productId(), data.date(),
                            StockLedgerType.SALE, mainQty.negate(), null, null, sale.getId(), memoNote);
                    if (stock.status() == StockLedgerService.Status.INSUFFICIENT_STOCK) {
                        throw new Abort(new Result(Status.INSUFFICIENT_STOCK, null, l.productId(), null, stock.available()));
                    }
                    TransactionItem item = new TransactionItem();
                    item.setTransaction(sale);
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

                // What the customer owes for the goods, then what they paid
                // A free sale (total 0) moves only stock; the ledger takes no 0 row
                if (party != null && totals.grandTotal().signum() > 0) {
                    partyLedger.post(session, party.getId(), data.date(), PartyLedgerType.SALE,
                            totals.grandTotal(), BigDecimal.ZERO, sale.getId(), memoNote);
                }
                // One payment row per account, even if the same account was sent twice
                Map<Long, BigDecimal> byAccount = new java.util.TreeMap<>();
                for (PaymentData p : data.payments()) {
                    byAccount.merge(p.accountId(), p.amount(), BigDecimal::add);
                }
                List<PaymentData> payments = new ArrayList<>();
                byAccount.forEach((accountId, amount) -> payments.add(new PaymentData(accountId, amount)));
                payments.sort(Comparator.comparingLong(PaymentData::accountId));
                PaymentMethod mainMethod = null;
                BigDecimal toSale = salePaid;
                for (PaymentData p : payments) {
                    if (p.amount().signum() <= 0) {
                        continue;
                    }
                    // The bill is paid first; the rest of the money is a plain payment to the party's ledger
                    BigDecimal part = p.amount().min(toSale);
                    if (part.signum() > 0) {
                        PaymentMethod method = receive(session, sale, party, p.accountId(), part, data.date(), memoNote);
                        toSale = toSale.subtract(part);
                        if (mainMethod == null) {
                            mainMethod = method;
                        }
                    }
                    BigDecimal rest = p.amount().subtract(part);
                    if (rest.signum() > 0) {
                        receiveForLedger(session, party, p.accountId(), rest, data.date(), memoNote);
                    }
                }
                sale.setPaymentMethod(mainMethod);

                auditService.created(session, AppModule.SALE, sale.getId(), "sale memo=" + memoNo
                        + " party=" + (party == null ? "walk-in" : party.getName()) + " total=" + totals.grandTotal()
                        + " paid=" + salePaid + " extra=" + extra + " due=" + due + " lines=" + data.lines().size());
                return Result.done(memoNo);
            });
        } catch (Abort a) {
            return a.result;
        }
    }

    /** An installment on a sale's due (11.5): money into an account, the party's ledger and the sale's due. */
    public Result collect(long saleId, long accountId, BigDecimal amount, LocalDate date, String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        return TransactionHelper.inTransaction(session -> {
            BusinessTransaction sale = lockSale(session, saleId);
            if (isInactive(sale)) {
                return Result.of(Status.INACTIVE_PARTY);
            }
            if (date.isBefore(sale.getDate())) {
                throw new IllegalArgumentException("Installment date is before the sale date");
            }
            if (sale.getDueAmount().compareTo(amount) < 0) {
                return new Result(Status.TOO_MUCH, null, null, null, sale.getDueAmount());
            }
            Party party = sale.getParty();
            String memoNote = "#" + sale.getMemoNo() + (note == null || note.isBlank() ? "" : " — " + note.trim());
            receive(session, sale, party, accountId, amount, date, memoNote);
            sale.setPaidAmount(sale.getPaidAmount().add(amount));
            sale.setDueAmount(sale.getDueAmount().subtract(amount));
            auditService.created(session, AppModule.SALE, sale.getId(),
                    "installment memo=" + sale.getMemoNo() + " amount=" + amount + " date=" + date);
            return Result.done(sale.getMemoNo());
        });
    }

    /**
     * A sales return (11.7): {@code quantities} by line id, in each line's unit. Stock comes back; the value of
     * the goods (with the sale's discount and VAT shared out) is credited to the party; {@code refund} of it may
     * be paid back from an account (a walk-in customer gets it all back).
     */
    public Result returnGoods(long saleId, Map<Long, BigDecimal> quantities, Long refundAccountId, BigDecimal refund,
                              LocalDate date, String note) {
        try {
            return TransactionHelper.inTransaction(session -> {
                BusinessTransaction sale = lockSale(session, saleId);
                if (isInactive(sale)) {
                    return Result.of(Status.INACTIVE_PARTY);
                }
                Party party = sale.getParty();
                List<TransactionItem> items = repository.items(session, saleId);
                items.sort(Comparator.comparingLong(i -> i.getProduct().getId()));
                // Share of the bill discount and VAT that each line's money carries
                BigDecimal factor = sale.getSubtotal().signum() == 0 ? BigDecimal.ONE
                        : sale.getGrandTotal().divide(sale.getSubtotal(), 10, RoundingMode.HALF_UP);
                String memoNote = "#" + sale.getMemoNo() + (note == null || note.isBlank() ? "" : " — " + note.trim());

                BigDecimal value = BigDecimal.ZERO;
                // What the goods returned in earlier returns were worth, to keep the total within the bill
                BigDecimal earlier = BigDecimal.ZERO;
                boolean anyBack = false;
                for (TransactionItem item : items) {
                    BigDecimal netPrice = item.getUnitPrice().subtract(item.getDiscountPerUnit());
                    earlier = earlier.add(item.getReturnedQuantity().multiply(netPrice));
                    BigDecimal qty = quantities.get(item.getId());
                    if (qty == null || qty.signum() <= 0) {
                        continue;
                    }
                    BigDecimal left = item.getQuantity().subtract(item.getReturnedQuantity());
                    if (qty.compareTo(left) > 0) {
                        throw new Abort(new Result(Status.TOO_MUCH, null, item.getProduct().getId(), null, left));
                    }
                    // Worked out on the total returned so far, so part returns add up to the line's stock exactly (N7)
                    BigDecimal mainQty = PurchaseService.mainShare(item, item.getReturnedQuantity().add(qty))
                            .subtract(PurchaseService.mainShare(item, item.getReturnedQuantity()));
                    stockLedger.post(session, item.getProduct().getId(), date, StockLedgerType.SALE_RETURN,
                            mainQty, null, null, sale.getId(), memoNote);
                    item.setReturnedQuantity(item.getReturnedQuantity().add(qty));
                    anyBack = true;
                    value = value.add(qty.multiply(netPrice));
                }
                value = value.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                // Never more than what the sale is still worth (rounding on the last return). Counted from the
                // goods, not from returned_amount, which also holds the refund roundings of earlier returns
                BigDecimal earlierValue = earlier.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                value = value.min(sale.getGrandTotal().subtract(earlierValue)).max(BigDecimal.ZERO);
                if (!anyBack) {
                    throw new IllegalArgumentException("Nothing to return");
                }

                // Stock is already written above, so a refusal must roll back (Abort), not return
                BigDecimal back = refund == null ? BigDecimal.ZERO : refund;
                // The goods first clear what is still due; only money actually paid can go back
                BigDecimal maxRefund = maxRefund(value, sale.getDueAmount());
                BigDecimal rounding = refundRounding(value, back, party != null, sale.getDueAmount());
                if (rounding == null) {
                    // A walk-in customer has no ledger: the whole value goes back in cash (give or take under ৳1)
                    throw new Abort(party == null
                            ? new Result(Status.DUE_WITHOUT_PARTY, null, null, null, value)
                            : new Result(Status.TOO_MUCH, null, null, null, maxRefund));
                }
                // What the return is worth in money: the goods' value and the coin rounding of the refund.
                // The rounding is a real loss (or gain), so it counts in returned_amount and with it in the profit
                BigDecimal settled = value.add(rounding);
                // Goods worth 0 (a free line) only come back to stock
                if (party != null && settled.signum() > 0) {
                    partyLedger.post(session, party.getId(), date, PartyLedgerType.RETURN,
                            BigDecimal.ZERO, settled, sale.getId(), memoNote);
                }
                if (back.signum() > 0) {
                    if (refundAccountId == null) {
                        throw new IllegalArgumentException("Refund account is required");
                    }
                    Account account = accountLedger.lock(session, refundAccountId);
                    BigDecimal available = accountLedger.availableOn(session, refundAccountId, date);
                    if (available.compareTo(back) < 0) {
                        throw new Abort(new Result(Status.INSUFFICIENT_BALANCE, null, null, refundAccountId, available));
                    }
                    AccountTransaction money = accountLedger.post(session, account.getId(), date,
                            AccountTransactionType.DEBIT, back, AccountTransactionSource.SALE, sale.getId(), memoNote);
                    if (party != null) {
                        partyLedger.post(session, party.getId(), date, PartyLedgerType.PAYMENT,
                                back, BigDecimal.ZERO, money.getId(), memoNote);
                    }
                }
                // What was not paid back lowers the sale's due (what is left over stays in the party's ledger)
                BigDecimal credit = settled.subtract(back);
                sale.setDueAmount(sale.getDueAmount().subtract(credit).max(BigDecimal.ZERO));
                sale.setReturnedAmount(sale.getReturnedAmount().add(settled));

                auditService.created(session, AppModule.SALE, sale.getId(), "return memo=" + sale.getMemoNo()
                        + " value=" + value + " refund=" + back + " rounding=" + rounding + " date=" + date);
                return new Result(Status.DONE, sale.getMemoNo(), null, null, value);
            });
        } catch (Abort a) {
            return a.result;
        }
    }

    /**
     * Money received for a sale: into the account, a payment row on the sale and, with a party, a credit in
     * its ledger (pointing at the account entry, so the ledger names the account).
     */
    private PaymentMethod receive(Session session, BusinessTransaction sale, Party party, long accountId,
                                  BigDecimal amount, LocalDate date, String note) {
        Account account = accountLedger.lock(session, accountId);
        AccountTransaction money = accountLedger.post(session, accountId, date, AccountTransactionType.CREDIT,
                amount, AccountTransactionSource.SALE, sale.getId(), note);
        PaymentMethod method = methodOf(account);
        TransactionPayment payment = new TransactionPayment();
        payment.setTransaction(sale);
        payment.setDate(date);
        payment.setAmount(amount);
        payment.setPaymentMethod(method);
        payment.setAccount(account);
        payment.setNote(note);
        repository.save(session, payment);
        if (party != null) {
            partyLedger.post(session, party.getId(), date, PartyLedgerType.RECEIPT,
                    BigDecimal.ZERO, amount, money.getId(), note);
        }
        return method;
    }

    /**
     * Money a party paid beyond the bill: like "টাকা পেলাম" on the parties page (account in, party ledger
     * credit), not part of the sale. The two entries point at each other.
     */
    private void receiveForLedger(Session session, Party party, long accountId, BigDecimal amount, LocalDate date,
                                  String note) {
        accountLedger.lock(session, accountId);
        AccountTransaction money = accountLedger.post(session, accountId, date, AccountTransactionType.CREDIT,
                amount, AccountTransactionSource.PARTY, null, note);
        PartyLedger entry = partyLedger.post(session, party.getId(), date, PartyLedgerType.RECEIPT,
                BigDecimal.ZERO, amount, money.getId(), note);
        money.setReferenceId(entry.getId());
    }

    /** Locks the sale's party (if any) and then the sale row, in that order. */
    private BusinessTransaction lockSale(Session session, long saleId) {
        BusinessTransaction sale = repository.find(session, saleId)
                .orElseThrow(() -> new IllegalStateException("Sale not found: " + saleId));
        if (sale.getParty() != null) {
            partyLedger.lock(session, sale.getParty().getId());
        }
        session.lock(sale, LockModeType.PESSIMISTIC_WRITE);
        session.refresh(sale);
        return sale;
    }

    /** An inactive party's ledger must not move (it is hidden from money screens); make it active first. */
    private static boolean isInactive(BusinessTransaction sale) {
        return sale.getParty() != null && sale.getParty().getStatus() == PartyStatus.INACTIVE;
    }

    /**
     * The coin rounding of a refund: money paid beyond the goods' value (+) or kept short of it (−), always under
     * ৳1, so a ৳0.70 piece can go back as ৳1 or as nothing. A walk-in customer (no ledger) may get a little more or
     * less than the value; a party only a little more, and only when the sale has no due (less stays in its
     * ledger as credit anyway). Null = the refund is not allowed.
     */
    public static BigDecimal refundRounding(BigDecimal value, BigDecimal back, boolean hasParty, BigDecimal due) {
        if (!hasParty) {
            BigDecimal diff = back.subtract(value);
            return diff.abs().compareTo(BigDecimal.ONE) < 0 ? diff : null;
        }
        if (back.compareTo(maxRefund(value, due)) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal extra = back.subtract(value);
        return due.signum() == 0 && extra.signum() > 0 && extra.compareTo(BigDecimal.ONE) < 0 ? extra : null;
    }

    /** Most money that can go back on a return worth {@code value}: the part above what is still due. */
    public static BigDecimal maxRefund(BigDecimal value, BigDecimal due) {
        return value.subtract(due).max(BigDecimal.ZERO);
    }

    /** The unit must be the product's unit or its secondary unit. */
    static Unit unitOf(Product product, long unitId) {
        if (product.getUnit() != null && product.getUnit().getId() == unitId) {
            return product.getUnit();
        }
        if (product.getSecondaryUnit() != null && product.getSecondaryUnit().getId() == unitId) {
            return product.getSecondaryUnit();
        }
        throw new IllegalArgumentException("Unit " + unitId + " is not a unit of product " + product.getId());
    }

    /** A quantity in the product's main unit (stock is kept in it). */
    public static BigDecimal toMain(Product product, Unit unit, BigDecimal quantity) {
        boolean secondary = product.getSecondaryUnit() != null && product.getSecondaryUnit().getId().equals(unit.getId())
                && (product.getUnit() == null || !product.getUnit().getId().equals(unit.getId()));
        if (!secondary) {
            return quantity.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return quantity.divide(product.getConversionFactor(), QTY_SCALE, RoundingMode.HALF_UP);
    }

    static PaymentMethod methodOf(Account account) {
        AccountNumberRule rule = account.getType() == null ? AccountNumberRule.NONE : account.getType().getNumberRule();
        return switch (rule) {
            case NONE -> PaymentMethod.CASH;
            case BANK -> PaymentMethod.BANK;
            case MOBILE -> PaymentMethod.MOBILE;
        };
    }

    /** The user's note, with a walk-in customer's name and mobile in front ("Name (01...) — note"). */
    private static String note(SaleData data) {
        String who = "";
        if (data.partyId() == null) {
            String name = data.walkInName() == null ? "" : data.walkInName().trim();
            String phone = data.walkInPhone() == null ? "" : data.walkInPhone().trim();
            who = name + (phone.isEmpty() ? "" : (name.isEmpty() ? "" : " ") + "(" + phone + ")");
        }
        String note = data.note() == null ? "" : data.note().trim();
        String all = who.isEmpty() ? note : note.isEmpty() ? who : who + " — " + note;
        return all.isEmpty() ? null : all.length() > 255 ? all.substring(0, 255) : all;
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
