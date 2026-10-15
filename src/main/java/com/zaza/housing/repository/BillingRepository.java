package com.zaza.housing.repository;

import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingCurrencySummaryResponse;
import com.zaza.housing.dto.BillingEntryResponse;
import com.zaza.housing.dto.DepositSettlementResponse;
import com.zaza.housing.dto.InvoiceResponse;
import com.zaza.housing.dto.PaymentAllocationResponse;
import com.zaza.housing.dto.PaymentClaimResponse;
import com.zaza.housing.dto.PaymentResponse;
import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.dto.RefundResponse;
import com.zaza.housing.dto.ReportPaymentRequest;
import com.zaza.housing.enums.BillingPolicy;
import com.zaza.housing.enums.InvoiceKind;
import com.zaza.housing.enums.PaymentChannel;
import com.zaza.housing.model.BillingAccount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BillingRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<BillingAccount> ACCOUNT =
      (r, n) ->
          new BillingAccount(
              r.getObject("lease_id", UUID.class),
              r.getObject("revision_id", UUID.class),
              r.getString("currency"),
              BillingPolicy.valueOf(r.getString("policy")),
              r.getString("status"),
              r.getLong("version"));
  private static final String INVOICES =
      """
      SELECT i.*, COALESCE((SELECT sum(c.amount) FROM billing_credits c WHERE c.invoice_id=i.id),0) AS credited,
       COALESCE((SELECT sum(a.amount) FROM payment_allocations a WHERE a.invoice_id=i.id AND NOT EXISTS
        (SELECT 1 FROM payment_allocation_reversals v WHERE v.allocation_id=a.id)),0) AS paid
      FROM billing_invoices i
      """;
  private static final String PAYMENTS =
      """
      SELECT p.*, EXISTS(SELECT 1 FROM payment_voids v WHERE v.payment_id=p.id) AS voided,
       COALESCE((SELECT sum(a.amount) FROM payment_allocations a WHERE a.payment_id=p.id AND NOT EXISTS
        (SELECT 1 FROM payment_allocation_reversals v WHERE v.allocation_id=a.id)),0) AS allocated,
       COALESCE((SELECT sum(r.amount) FROM payment_refunds r WHERE r.payment_id=p.id),0) AS refunded
      FROM payment_receipts p
      """;
  private static final String CLAIMS =
      """
      SELECT c.*,CASE WHEN d.accepted THEN 'VERIFIED' WHEN d.accepted=false THEN 'REJECTED' ELSE 'PENDING' END AS status,
       d.reason AS decision_reason,p.id AS payment_id FROM payment_claims c
       LEFT JOIN payment_claim_decisions d ON d.claim_id=c.id LEFT JOIN payment_receipts p ON p.claim_id=c.id
      """;
  private static final String SETTLEMENTS =
      """
      SELECT s.*,COALESCE(d.decision,'PENDING') AS status,d.reason AS decision_reason
       FROM deposit_settlements s LEFT JOIN deposit_settlement_decisions d ON d.settlement_id=s.id
      """;
  private static final RowMapper<PaymentResponse> PAYMENT =
      (r, n) -> {
        var amount = r.getBigDecimal("amount");
        var allocated = r.getBigDecimal("allocated");
        var refunded = r.getBigDecimal("refunded");
        boolean voided = r.getBoolean("voided");
        return new PaymentResponse(
            r.getObject("id", UUID.class),
            r.getObject("claim_id", UUID.class),
            PaymentChannel.valueOf(r.getString("channel")),
            r.getString("reference"),
            r.getDate("received_on").toLocalDate(),
            amount,
            allocated,
            refunded,
            voided ? BigDecimal.ZERO : amount.subtract(allocated).subtract(refunded),
            voided,
            r.getObject("verified_by", UUID.class),
            r.getTimestamp("created_at").toInstant());
      };
  private static final RowMapper<PaymentClaimResponse> CLAIM =
      (r, n) ->
          new PaymentClaimResponse(
              r.getObject("id", UUID.class),
              r.getBigDecimal("amount"),
              PaymentChannel.valueOf(r.getString("channel")),
              r.getString("reference"),
              r.getDate("received_on").toLocalDate(),
              r.getString("reason"),
              r.getString("status"),
              r.getObject("payment_id", UUID.class),
              r.getString("decision_reason"),
              r.getTimestamp("created_at").toInstant());
  private static final RowMapper<PaymentAllocationResponse> ALLOCATION =
      (r, n) ->
          new PaymentAllocationResponse(
              r.getObject("id", UUID.class),
              r.getObject("payment_id", UUID.class),
              r.getObject("invoice_id", UUID.class),
              r.getBigDecimal("amount"),
              r.getBoolean("reversed"),
              r.getTimestamp("created_at").toInstant());
  private static final RowMapper<DepositSettlementResponse> SETTLEMENT =
      (r, n) ->
          new DepositSettlementResponse(
              r.getObject("id", UUID.class),
              r.getBigDecimal("held"),
              r.getBigDecimal("deduction"),
              r.getBigDecimal("held").subtract(r.getBigDecimal("deduction")),
              r.getString("reason"),
              r.getString("status"),
              r.getString("decision_reason"),
              r.getTimestamp("created_at").toInstant());

  public BillingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<BillingAccount> find(UUID lease) {
    return jdbc.query("SELECT * FROM billing_accounts WHERE lease_id=?", ACCOUNT, lease).stream()
        .findFirst();
  }

  public void open(UUID lease, UUID revision, String currency, BillingPolicy policy) {
    jdbc.update(
        "INSERT INTO billing_accounts(lease_id,revision_id,currency,policy) VALUES (?,?,?,?)",
        lease,
        revision,
        currency,
        policy.name());
  }

  public void touch(UUID lease) {
    jdbc.update("UPDATE billing_accounts SET version=version+1 WHERE lease_id=?", lease);
  }

  public void finalizeAccount(UUID lease) {
    jdbc.update("UPDATE billing_accounts SET status='FINALIZED' WHERE lease_id=?", lease);
  }

  public Optional<String> operation(UUID lease, UUID request, UUID actor, String action) {
    return jdbc
        .query(
            "SELECT CASE WHEN actor_id=? AND action=? THEN fingerprint ELSE 'MISMATCH' END FROM"
                + " billing_operations WHERE lease_id=? AND request_id=?",
            (r, n) -> r.getString(1),
            actor,
            action,
            lease,
            request)
        .stream()
        .findFirst();
  }

  public void operation(UUID lease, UUID request, UUID actor, String action, String fingerprint) {
    jdbc.update(
        "INSERT INTO billing_operations(lease_id,request_id,actor_id,action,fingerprint) VALUES"
            + " (?,?,?,?,?)",
        lease,
        request,
        actor,
        action,
        fingerprint);
  }

  public UUID invoice(
      UUID lease,
      InvoiceKind kind,
      String description,
      LocalDate due,
      LocalDate start,
      LocalDate end,
      BigDecimal amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " billing_invoices(id,lease_id,kind,description,due_date,period_start,period_end,amount)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        lease,
        kind.name(),
        description,
        due,
        start,
        end,
        amount);
    return id;
  }

  public List<InvoiceResponse> invoices(UUID lease, LocalDate today, int limit, int offset) {
    return jdbc.query(
        INVOICES + " WHERE i.lease_id=? ORDER BY i.due_date,i.created_at,i.id LIMIT ? OFFSET ?",
        invoiceMapper(today),
        lease,
        limit,
        offset);
  }

  public List<InvoiceResponse> scheduledInvoices(UUID lease, LocalDate today) {
    // Scheduled rent/deposit rows are bounded by the billing policy; inspection charges are not.
    return jdbc.query(
        INVOICES + " WHERE i.lease_id=? AND i.kind IN ('RENT','DEPOSIT') ORDER BY i.due_date,i.id",
        invoiceMapper(today),
        lease);
  }

  public Optional<InvoiceResponse> invoice(UUID lease, UUID id, LocalDate today) {
    return jdbc
        .query(INVOICES + " WHERE i.lease_id=? AND i.id=?", invoiceMapper(today), lease, id)
        .stream()
        .findFirst();
  }

  private RowMapper<InvoiceResponse> invoiceMapper(LocalDate today) {
    return (r, n) -> {
      BigDecimal amount = r.getBigDecimal("amount"),
          credit = r.getBigDecimal("credited"),
          paid = r.getBigDecimal("paid"),
          out = amount.subtract(credit).subtract(paid);
      LocalDate due = r.getDate("due_date").toLocalDate();
      String status =
          out.signum() == 0
              ? (credit.compareTo(amount) == 0 ? "CREDITED" : "PAID")
              : due.isBefore(today) ? "OVERDUE" : paid.signum() > 0 ? "PARTIAL" : "OPEN";
      return new InvoiceResponse(
          r.getObject("id", UUID.class),
          InvoiceKind.valueOf(r.getString("kind")),
          r.getString("description"),
          due,
          r.getDate("period_start") == null ? null : r.getDate("period_start").toLocalDate(),
          r.getDate("period_end") == null ? null : r.getDate("period_end").toLocalDate(),
          amount,
          credit,
          paid,
          out,
          status,
          r.getTimestamp("created_at").toInstant());
    };
  }

  public void credit(UUID lease, UUID invoice, BigDecimal amount, String reason) {
    jdbc.update(
        "INSERT INTO billing_credits(id,lease_id,invoice_id,amount,reason) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        lease,
        invoice,
        amount,
        reason);
  }

  public UUID claim(UUID lease, UUID actor, ReportPaymentRequest r, String reference) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " payment_claims(id,lease_id,actor_id,amount,channel,reference,received_on,reason)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        lease,
        actor,
        r.amount(),
        r.channel().name(),
        reference,
        r.receivedOn(),
        r.reason().trim());
    return id;
  }

  public List<PaymentClaimResponse> claims(UUID lease, int limit, int offset) {
    return jdbc.query(
        CLAIMS + " WHERE c.lease_id=? ORDER BY c.created_at DESC,c.id LIMIT ? OFFSET ?",
        CLAIM,
        lease,
        limit,
        offset);
  }

  public Optional<PaymentClaimResponse> claim(UUID lease, UUID id) {
    return jdbc.query(CLAIMS + " WHERE c.lease_id=? AND c.id=?", CLAIM, lease, id).stream()
        .findFirst();
  }

  public void decideClaim(UUID claim, UUID actor, boolean accept, String reason) {
    jdbc.update(
        "INSERT INTO payment_claim_decisions(claim_id,actor_id,accepted,reason) VALUES (?,?,?,?)",
        claim,
        actor,
        accept,
        reason);
  }

  public void reference(UUID owner, PaymentChannel channel, String reference) {
    jdbc.update(
        "INSERT INTO payment_references(owner_organization_id,channel,reference) VALUES (?,?,?)",
        owner,
        channel.name(),
        reference);
  }

  public UUID receipt(
      UUID lease,
      UUID claim,
      UUID actor,
      PaymentChannel channel,
      String reference,
      LocalDate date,
      BigDecimal amount,
      String reason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " payment_receipts(id,lease_id,claim_id,verified_by,channel,reference,received_on,amount,reason)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        lease,
        claim,
        actor,
        channel.name(),
        reference,
        date,
        amount,
        reason);
    return id;
  }

  public Optional<PaymentResponse> payment(UUID lease, UUID id) {
    return jdbc.query(PAYMENTS + " WHERE p.lease_id=? AND p.id=?", PAYMENT, lease, id).stream()
        .findFirst();
  }

  public List<PaymentResponse> payments(UUID lease, int limit, int offset) {
    return jdbc.query(
        PAYMENTS + " WHERE p.lease_id=? ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",
        PAYMENT,
        lease,
        limit,
        offset);
  }

  public UUID allocate(UUID lease, UUID payment, UUID invoice, BigDecimal amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payment_allocations(id,lease_id,payment_id,invoice_id,amount) VALUES"
            + " (?,?,?,?,?)",
        id,
        lease,
        payment,
        invoice,
        amount);
    return id;
  }

  public List<PaymentAllocationResponse> allocations(UUID lease, int limit, int offset) {
    return jdbc.query(
        "SELECT a.*,EXISTS(SELECT 1 FROM payment_allocation_reversals v WHERE v.allocation_id=a.id)"
            + " AS reversed FROM payment_allocations a WHERE a.lease_id=? ORDER BY a.created_at"
            + " DESC,a.id LIMIT ? OFFSET ?",
        ALLOCATION,
        lease,
        limit,
        offset);
  }

  public List<PaymentAllocationResponse> invoiceAllocations(UUID lease, UUID invoice) {
    return jdbc.query(
        "SELECT a.*,false AS reversed FROM payment_allocations a WHERE a.lease_id=? AND"
            + " a.invoice_id=? AND NOT EXISTS(SELECT 1 FROM payment_allocation_reversals v WHERE"
            + " v.allocation_id=a.id) ORDER BY a.created_at DESC,a.id",
        ALLOCATION,
        lease,
        invoice);
  }

  public List<PaymentAllocationResponse> paymentAllocations(UUID lease, UUID payment) {
    return jdbc.query(
        "SELECT a.*,EXISTS(SELECT 1 FROM payment_allocation_reversals v WHERE v.allocation_id=a.id)"
            + " AS reversed FROM payment_allocations a WHERE a.lease_id=? AND a.payment_id=? ORDER"
            + " BY a.created_at,a.id",
        ALLOCATION,
        lease,
        payment);
  }

  public Optional<PaymentAllocationResponse> allocation(UUID lease, UUID id) {
    return jdbc
        .query(
            "SELECT a.*,EXISTS(SELECT 1 FROM payment_allocation_reversals v WHERE"
                + " v.allocation_id=a.id) AS reversed FROM payment_allocations a WHERE a.lease_id=?"
                + " AND a.id=?",
            ALLOCATION,
            lease,
            id)
        .stream()
        .findFirst();
  }

  public void reverse(UUID allocation, String reason) {
    jdbc.update(
        "INSERT INTO payment_allocation_reversals(allocation_id,reason) VALUES (?,?)",
        allocation,
        reason);
  }

  public void voidPayment(UUID payment, String reason) {
    jdbc.update("INSERT INTO payment_voids(payment_id,reason) VALUES (?,?)", payment, reason);
  }

  public UUID refund(
      UUID lease, UUID payment, boolean deposit, RecordRefundRequest r, String reference) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " payment_refunds(id,lease_id,payment_id,deposit,amount,channel,reference,paid_on,reason)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        lease,
        payment,
        deposit,
        r.amount(),
        r.channel().name(),
        reference,
        r.receivedOn(),
        r.reason().trim());
    return id;
  }

  public List<RefundResponse> refunds(UUID lease, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM payment_refunds WHERE lease_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET"
            + " ?",
        (r, n) ->
            new RefundResponse(
                r.getObject("id", UUID.class),
                r.getObject("payment_id", UUID.class),
                r.getBoolean("deposit"),
                r.getBigDecimal("amount"),
                PaymentChannel.valueOf(r.getString("channel")),
                r.getString("reference"),
                r.getDate("paid_on").toLocalDate(),
                r.getString("reason"),
                r.getTimestamp("created_at").toInstant()),
        lease,
        limit,
        offset);
  }

  public UUID settlement(UUID lease, BigDecimal held, BigDecimal deduction, String reason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO deposit_settlements(id,lease_id,held,deduction,reason) VALUES (?,?,?,?,?)",
        id,
        lease,
        held,
        deduction,
        reason);
    return id;
  }

  public List<DepositSettlementResponse> settlements(UUID lease, int limit, int offset) {
    return jdbc.query(
        SETTLEMENTS + " WHERE s.lease_id=? ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?",
        SETTLEMENT,
        lease,
        limit,
        offset);
  }

  public Optional<DepositSettlementResponse> settlement(UUID lease, UUID id) {
    return jdbc
        .query(SETTLEMENTS + " WHERE s.lease_id=? AND s.id=?", SETTLEMENT, lease, id)
        .stream()
        .findFirst();
  }

  public boolean hasSettlement(UUID lease) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM deposit_settlements s LEFT JOIN"
                + " deposit_settlement_decisions d ON d.settlement_id=s.id WHERE s.lease_id=? AND"
                + " (d.decision IS NULL OR d.decision='ACCEPT'))",
            Boolean.class,
            lease));
  }

  public boolean acceptedSettlement(UUID lease) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM deposit_settlements s JOIN deposit_settlement_decisions d"
                + " ON d.settlement_id=s.id AND d.decision='ACCEPT' WHERE s.lease_id=?)",
            Boolean.class,
            lease));
  }

  public void decideSettlement(UUID id, UUID actor, String decision, String reason) {
    jdbc.update(
        "INSERT INTO deposit_settlement_decisions(settlement_id,actor_id,decision,reason) VALUES"
            + " (?,?,?,?)",
        id,
        actor,
        decision,
        reason);
  }

  public BigDecimal deducted(UUID lease) {
    return jdbc.queryForObject(
        "SELECT COALESCE(sum(s.deduction),0) FROM deposit_settlements s JOIN"
            + " deposit_settlement_decisions d ON d.settlement_id=s.id AND d.decision='ACCEPT'"
            + " WHERE s.lease_id=?",
        BigDecimal.class,
        lease);
  }

  public BigDecimal depositRefunded(UUID lease) {
    return jdbc.queryForObject(
        "SELECT COALESCE(sum(amount),0) FROM payment_refunds WHERE lease_id=? AND deposit",
        BigDecimal.class,
        lease);
  }

  public UUID event(UUID lease, UUID actor, String type, UUID entity, String message) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO billing_events(id,lease_id,actor_id,type,entity_id,message) VALUES"
            + " (?,?,?,?,?,?)",
        id,
        lease,
        actor,
        type,
        entity,
        message);
    return id;
  }

  public List<BillingEntryResponse> history(UUID lease, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM billing_events WHERE lease_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET"
            + " ?",
        (r, n) ->
            new BillingEntryResponse(
                r.getObject("id", UUID.class),
                r.getString("type"),
                r.getObject("entity_id", UUID.class),
                r.getObject("actor_id", UUID.class),
                r.getString("message"),
                r.getTimestamp("created_at").toInstant()),
        lease,
        limit,
        offset);
  }

  public boolean moveInOutstanding(UUID lease, LocalDate date) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ("
                + INVOICES
                + " WHERE i.lease_id=?) t WHERE amount-credited-paid>0 AND (kind='DEPOSIT' OR"
                + " (kind='RENT' AND due_date<=?)))",
            Boolean.class,
            lease,
            date));
  }

  public long pendingClaims(UUID lease) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payment_claims c WHERE c.lease_id=? AND NOT EXISTS(SELECT 1 FROM"
            + " payment_claim_decisions d WHERE d.claim_id=c.id)",
        Long.class,
        lease);
  }

  public List<RefundResponse> paymentRefunds(UUID lease, UUID payment) {
    return jdbc.query(
        "SELECT * FROM payment_refunds WHERE lease_id=? AND payment_id=? ORDER BY created_at,id",
        (r, n) ->
            new RefundResponse(
                r.getObject("id", UUID.class),
                r.getObject("payment_id", UUID.class),
                r.getBoolean("deposit"),
                r.getBigDecimal("amount"),
                PaymentChannel.valueOf(r.getString("channel")),
                r.getString("reference"),
                r.getDate("paid_on").toLocalDate(),
                r.getString("reason"),
                r.getTimestamp("created_at").toInstant()),
        lease,
        payment);
  }

  public List<BillingCurrencySummaryResponse> report(UUID scope, boolean tenant, LocalDate today) {
    String sql =
        """
        WITH scoped AS (SELECT b.* FROM billing_accounts b JOIN rental_leases l ON l.id=b.lease_id WHERE
        """
            + (tenant ? "l.tenant_id=?" : "l.property_id=?")
            + """
            )
            SELECT b.currency,count(*) AS leases,
             sum(i.outstanding) AS outstanding,sum(i.due_now) AS due_now,sum(i.overdue) AS overdue,
             sum(i.rent) AS rent_collected,sum(i.deposit-d.deducted-r.deposit_refunded) AS deposit_held,
             sum(p.received) AS received,sum(r.refunded) AS refunded,
             sum(p.received-i.allocated-r.refunded+r.deposit_refunded) AS unallocated
            FROM scoped b
            CROSS JOIN LATERAL (
             SELECT COALESCE(sum(amount-credited-paid),0) AS outstanding,
              COALESCE(sum(CASE WHEN due_date<=? THEN amount-credited-paid ELSE 0 END),0) AS due_now,
              COALESCE(sum(CASE WHEN due_date<? THEN amount-credited-paid ELSE 0 END),0) AS overdue,
              COALESCE(sum(paid),0) AS allocated,
              COALESCE(sum(CASE WHEN kind='RENT' THEN paid ELSE 0 END),0) AS rent,
              COALESCE(sum(CASE WHEN kind='DEPOSIT' THEN paid ELSE 0 END),0) AS deposit
             FROM (
            """
            + INVOICES
            + " WHERE i.lease_id=b.lease_id) invoices) i "
            + """
            CROSS JOIN LATERAL (
             SELECT COALESCE(sum(amount),0) AS received FROM payment_receipts p WHERE p.lease_id=b.lease_id
              AND NOT EXISTS(SELECT 1 FROM payment_voids v WHERE v.payment_id=p.id)
            ) p
            CROSS JOIN LATERAL (
             SELECT COALESCE(sum(amount),0) AS refunded,COALESCE(sum(CASE WHEN deposit THEN amount ELSE 0 END),0) AS deposit_refunded
             FROM payment_refunds r WHERE r.lease_id=b.lease_id
            ) r
            CROSS JOIN LATERAL (
             SELECT COALESCE(sum(s.deduction),0) AS deducted FROM deposit_settlements s
              JOIN deposit_settlement_decisions d ON d.settlement_id=s.id AND d.decision='ACCEPT' WHERE s.lease_id=b.lease_id
            ) d
            GROUP BY b.currency ORDER BY b.currency
            """;
    return jdbc.query(
        sql,
        (r, n) ->
            new BillingCurrencySummaryResponse(
                r.getString("currency"),
                r.getLong("leases"),
                r.getBigDecimal("outstanding"),
                r.getBigDecimal("due_now"),
                r.getBigDecimal("overdue"),
                r.getBigDecimal("rent_collected"),
                r.getBigDecimal("deposit_held"),
                r.getBigDecimal("received"),
                r.getBigDecimal("refunded"),
                r.getBigDecimal("unallocated")),
        scope,
        today,
        today);
  }

  public BillingAccountResponse summary(UUID lease, LocalDate today) {
    var account = find(lease).orElseThrow();
    var totals =
        jdbc.queryForMap(
            """
              SELECT COALESCE(sum(amount),0) AS invoiced,COALESCE(sum(credited),0) AS credited,COALESCE(sum(paid),0) AS allocated,
               COALESCE(sum(amount-credited-paid),0) AS outstanding,
               COALESCE(sum(CASE WHEN due_date<=? THEN amount-credited-paid ELSE 0 END),0) AS due_now,
               COALESCE(sum(CASE WHEN due_date<? THEN amount-credited-paid ELSE 0 END),0) AS overdue,
               COALESCE(sum(CASE WHEN kind='RENT' THEN paid ELSE 0 END),0) AS rent,
               COALESCE(sum(CASE WHEN kind='DEPOSIT' THEN paid ELSE 0 END),0) AS deposit
              FROM (
            """
                + INVOICES
                + " WHERE i.lease_id=?) t",
            today,
            today,
            lease);
    BigDecimal received =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(p.amount),0) FROM payment_receipts p WHERE p.lease_id=? AND NOT"
                + " EXISTS(SELECT 1 FROM payment_voids v WHERE v.payment_id=p.id)",
            BigDecimal.class,
            lease);
    BigDecimal refunded =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(amount),0) FROM payment_refunds WHERE lease_id=?",
            BigDecimal.class,
            lease);
    BigDecimal allocated = (BigDecimal) totals.get("allocated"),
        outstanding = (BigDecimal) totals.get("outstanding"),
        deducted = deducted(lease),
        depositRefunded = depositRefunded(lease);
    BigDecimal credit = received.subtract(allocated).subtract(refunded.subtract(depositRefunded));
    BigDecimal held =
        ((BigDecimal) totals.get("deposit")).subtract(deducted).subtract(depositRefunded);
    long pending = pendingClaims(lease);
    boolean ready =
        account.status().equals("FINALIZED")
            && outstanding.signum() == 0
            && credit.signum() == 0
            && held.signum() == 0
            && pending == 0;
    return new BillingAccountResponse(
        lease,
        account.revisionId(),
        account.currency(),
        account.policy(),
        account.status(),
        account.version(),
        (BigDecimal) totals.get("invoiced"),
        (BigDecimal) totals.get("credited"),
        allocated,
        outstanding,
        (BigDecimal) totals.get("due_now"),
        (BigDecimal) totals.get("overdue"),
        received,
        refunded,
        credit,
        (BigDecimal) totals.get("rent"),
        held,
        deducted,
        pending,
        ready);
  }
}
