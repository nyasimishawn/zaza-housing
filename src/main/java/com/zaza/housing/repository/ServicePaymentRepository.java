package com.zaza.housing.repository;

import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.dto.ReportPaymentRequest;
import com.zaza.housing.dto.ServiceClaimResponse;
import com.zaza.housing.dto.ServiceFinanceEventResponse;
import com.zaza.housing.dto.ServiceFinanceSummaryResponse;
import com.zaza.housing.dto.ServiceReceiptResponse;
import com.zaza.housing.dto.ServiceRefundResponse;
import com.zaza.housing.enums.PaymentChannel;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.ServiceAccountBalance;
import com.zaza.housing.model.ServicePaymentAccount;
import com.zaza.housing.model.WorkRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ServicePaymentRepository {
  private final JdbcTemplate jdbc;

  public ServicePaymentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<ServicePaymentAccount> ACCOUNT =
      (r, n) ->
          new ServicePaymentAccount(
              id(r, "id"),
              id(r, "work_id"),
              id(r, "assignment_id"),
              id(r, "quote_id"),
              id(r, "provider_id"),
              id(r, "payer_user_id"),
              id(r, "payer_organization_id"),
              r.getString("currency"),
              r.getBigDecimal("amount"),
              r.getInt("commission_bps"),
              r.getBigDecimal("fee"),
              r.getString("status"),
              r.getLong("version"),
              r.getTimestamp("created_at").toInstant());

  private static UUID id(ResultSet r, String c) throws SQLException {
    return r.getObject(c, UUID.class);
  }

  public Optional<LocalDate> latestReceiptDate(UUID account) {
    return Optional.ofNullable(
        jdbc.queryForObject(
            "SELECT max(received_on) FROM service_payment_receipts r WHERE account_id=? AND NOT"
                + " EXISTS(SELECT 1 FROM service_receipt_voids v WHERE v.receipt_id=r.id)",
            LocalDate.class,
            account));
  }

  public Optional<ServicePaymentAccount> find(UUID id) {
    return jdbc.query("SELECT * FROM service_payment_accounts WHERE id=?", ACCOUNT, id).stream()
        .findFirst();
  }

  public Optional<ServicePaymentAccount> quote(UUID id) {
    return jdbc
        .query("SELECT * FROM service_payment_accounts WHERE quote_id=?", ACCOUNT, id)
        .stream()
        .findFirst();
  }

  public void create(
      UUID id, WorkRequest w, BigDecimal amount, String currency, int bps, BigDecimal fee) {
    jdbc.update(
        "INSERT INTO"
            + " service_payment_accounts(id,work_id,assignment_id,quote_id,provider_id,payer_user_id,payer_organization_id,currency,amount,commission_bps,fee)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id,
        w.id(),
        w.assignmentId(),
        w.quoteId(),
        w.providerId(),
        w.kind() == com.zaza.housing.enums.WorkKind.SERVICE_BOOKING ? w.tenantId() : null,
        w.kind() == com.zaza.housing.enums.WorkKind.MAINTENANCE ? w.ownerOrganizationId() : null,
        currency,
        amount,
        bps,
        fee);
  }

  public void touch(UUID id) {
    jdbc.update("UPDATE service_payment_accounts SET version=version+1 WHERE id=?", id);
  }

  public boolean pendingClaims(UUID account) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM service_payment_claims c WHERE c.account_id=? AND NOT"
                + " EXISTS(SELECT 1 FROM service_claim_decisions d WHERE d.claim_id=c.id))",
            Boolean.class,
            account));
  }

  public void close(UUID id) {
    jdbc.update("UPDATE service_payment_accounts SET status='CLOSED' WHERE id=?", id);
  }

  public boolean blocksWorkChange(UUID work) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM service_payment_accounts WHERE work_id=? AND"
                + " status='OPEN')",
            Boolean.class,
            work));
  }

  public List<ServicePaymentAccount> list(
      UUID user, UUID payerOrg, UUID provider, int limit, int offset) {
    String where =
        user != null
            ? "payer_user_id=?"
            : payerOrg != null
                ? "payer_organization_id=?"
                : provider != null ? "provider_id=?" : "true";
    var args = new ArrayList<Object>();
    if (user != null || payerOrg != null || provider != null)
      args.add(user != null ? user : payerOrg != null ? payerOrg : provider);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM service_payment_accounts WHERE "
            + where
            + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        ACCOUNT,
        args.toArray());
  }

  public Map<UUID, ServiceAccountBalance> balances(List<UUID> ids) {
    var result = new HashMap<UUID, ServiceAccountBalance>();
    if (ids.isEmpty()) return result;
    jdbc.query(
        "SELECT * FROM service_account_balances WHERE id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")",
        r -> {
          result.put(
              id(r, "id"),
              new ServiceAccountBalance(
                  r.getBigDecimal("received"),
                  r.getBigDecimal("refunded"),
                  r.getBigDecimal("reserved"),
                  r.getBigDecimal("paid"),
                  r.getBigDecimal("fees_earned")));
        },
        ids.toArray());
    return result;
  }

  public ServiceAccountBalance balance(UUID id) {
    return balances(List.of(id)).get(id);
  }

  public Optional<Map<String, Object>> operation(UUID scope, UUID request) {
    return jdbc
        .queryForList(
            "SELECT * FROM service_finance_operations WHERE scope_id=? AND request_id=?",
            scope,
            request)
        .stream()
        .findFirst();
  }

  public void operation(
      UUID scope, UUID request, UUID actor, String action, String fingerprint, UUID entity) {
    jdbc.update(
        "INSERT INTO"
            + " service_finance_operations(scope_id,request_id,actor_id,action,fingerprint,entity_id)"
            + " VALUES (?,?,?,?,?,?)",
        scope,
        request,
        actor,
        action,
        fingerprint,
        entity);
  }

  public UUID event(
      UUID account, UUID actor, String type, UUID entity, BigDecimal amount, String message) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_finance_events(id,account_id,actor_id,type,entity_id,amount,message)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        account,
        actor,
        type,
        entity,
        amount,
        message);
    return id;
  }

  public List<ServiceFinanceEventResponse> history(UUID account, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM service_finance_events WHERE account_id=? ORDER BY created_at DESC,id LIMIT"
            + " ? OFFSET ?",
        (r, n) ->
            new ServiceFinanceEventResponse(
                id(r, "id"),
                r.getString("type"),
                id(r, "entity_id"),
                r.getBigDecimal("amount"),
                r.getString("message"),
                r.getTimestamp("created_at").toInstant()),
        account,
        limit,
        offset);
  }

  public void reference(PaymentChannel channel, String ref) {
    int n =
        jdbc.update(
            "INSERT INTO service_transfer_references(channel,reference) VALUES (?,?) ON CONFLICT DO"
                + " NOTHING",
            channel.name(),
            ref);
    if (n == 0)
      throw new ApiException(
          HttpStatus.CONFLICT, "This external transfer reference is already recorded.");
  }

  public UUID claim(UUID account, UUID actor, ReportPaymentRequest r, String ref) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " service_payment_claims(id,account_id,amount,channel,reference,received_on,reason,reported_by)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        account,
        r.amount(),
        r.channel().name(),
        ref,
        r.receivedOn(),
        r.reason().trim(),
        actor);
    return id;
  }

  private static final String CLAIMS =
      "SELECT c.*,d.accepted,d.reason AS decision_reason,p.id AS receipt_id FROM"
          + " service_payment_claims c LEFT JOIN service_claim_decisions d ON d.claim_id=c.id LEFT"
          + " JOIN service_payment_receipts p ON p.claim_id=c.id WHERE c.account_id=?";
  private static final RowMapper<ServiceClaimResponse> CLAIM =
      (r, n) ->
          new ServiceClaimResponse(
              id(r, "id"),
              r.getBigDecimal("amount"),
              PaymentChannel.valueOf(r.getString("channel")),
              r.getString("reference"),
              r.getObject("received_on", LocalDate.class),
              r.getString("reason"),
              r.getObject("accepted") == null
                  ? "PENDING"
                  : r.getBoolean("accepted") ? "ACCEPTED" : "REJECTED",
              r.getString("decision_reason"),
              id(r, "receipt_id"),
              r.getTimestamp("created_at").toInstant());

  public List<ServiceClaimResponse> claims(UUID a, int limit, int offset) {
    return jdbc.query(
        CLAIMS + " ORDER BY c.created_at DESC,c.id LIMIT ? OFFSET ?", CLAIM, a, limit, offset);
  }

  public Optional<ServiceClaimResponse> claim(UUID a, UUID id) {
    return jdbc.query(CLAIMS + " AND c.id=?", CLAIM, a, id).stream().findFirst();
  }

  public UUID claimReporter(UUID id) {
    return jdbc.queryForObject(
        "SELECT reported_by FROM service_payment_claims WHERE id=?", UUID.class, id);
  }

  public void decideClaim(UUID claim, UUID actor, boolean accepted, String reason) {
    jdbc.update(
        "INSERT INTO service_claim_decisions(claim_id,actor_id,accepted,reason) VALUES (?,?,?,?)",
        claim,
        actor,
        accepted,
        reason.trim());
  }

  public UUID receipt(
      UUID a,
      UUID claim,
      UUID actor,
      BigDecimal amount,
      PaymentChannel channel,
      String reference,
      LocalDate date,
      String reason) {
    UUID id = UUID.randomUUID();
    reference(channel, reference);
    jdbc.update(
        "INSERT INTO"
            + " service_payment_receipts(id,account_id,claim_id,amount,channel,reference,received_on,verified_by,reason)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        a,
        claim,
        amount,
        channel.name(),
        reference,
        date,
        actor,
        reason.trim());
    return id;
  }

  private static final String RECEIPTS =
      "SELECT r.*,EXISTS(SELECT 1 FROM service_receipt_voids v WHERE v.receipt_id=r.id) AS"
          + " voided,coalesce((SELECT sum(f.amount) FROM service_payment_refunds f WHERE"
          + " f.receipt_id=r.id),0) AS refunded FROM service_payment_receipts r WHERE"
          + " r.account_id=?";
  private static final RowMapper<ServiceReceiptResponse> RECEIPT =
      (r, n) ->
          new ServiceReceiptResponse(
              id(r, "id"),
              id(r, "claim_id"),
              r.getBigDecimal("amount"),
              PaymentChannel.valueOf(r.getString("channel")),
              r.getString("reference"),
              r.getObject("received_on", LocalDate.class),
              r.getBigDecimal("refunded"),
              r.getBoolean("voided"),
              r.getTimestamp("created_at").toInstant());

  public Optional<ServiceReceiptResponse> receipt(UUID a, UUID id) {
    return jdbc.query(RECEIPTS + " AND r.id=?", RECEIPT, a, id).stream().findFirst();
  }

  public List<ServiceReceiptResponse> receipts(UUID a, int limit, int offset) {
    return jdbc.query(
        RECEIPTS + " ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?", RECEIPT, a, limit, offset);
  }

  public void voidReceipt(UUID id, UUID actor, String reason) {
    jdbc.update(
        "INSERT INTO service_receipt_voids(receipt_id,actor_id,reason) VALUES (?,?,?)",
        id,
        actor,
        reason.trim());
  }

  public UUID refund(UUID a, UUID receipt, UUID actor, RecordRefundRequest r, String ref) {
    UUID id = UUID.randomUUID();
    reference(r.channel(), ref);
    jdbc.update(
        "INSERT INTO"
            + " service_payment_refunds(id,account_id,receipt_id,amount,channel,reference,paid_on,verified_by,reason)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        a,
        receipt,
        r.amount(),
        r.channel().name(),
        ref,
        r.receivedOn(),
        actor,
        r.reason().trim());
    return id;
  }

  public List<ServiceRefundResponse> refunds(UUID a, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM service_payment_refunds WHERE account_id=? ORDER BY created_at DESC,id LIMIT"
            + " ? OFFSET ?",
        (r, n) ->
            new ServiceRefundResponse(
                id(r, "id"),
                id(r, "receipt_id"),
                r.getBigDecimal("amount"),
                PaymentChannel.valueOf(r.getString("channel")),
                r.getString("reference"),
                r.getObject("paid_on", LocalDate.class),
                r.getString("reason"),
                r.getTimestamp("created_at").toInstant()),
        a,
        limit,
        offset);
  }

  public List<ServiceFinanceSummaryResponse> summary(UUID user, UUID org, UUID provider) {
    String where =
        user != null
            ? "a.payer_user_id=?"
            : org != null
                ? "a.payer_organization_id=?"
                : provider != null ? "a.provider_id=?" : "true";
    Object[] args =
        user != null || org != null || provider != null
            ? new Object[] {user != null ? user : org != null ? org : provider}
            : new Object[] {};
    return jdbc.query(
        "SELECT a.currency,count(*) AS accounts,sum(a.amount) AS quoted,sum(b.received) AS"
            + " received,sum(b.refunded) AS refunded,sum(b.reserved) AS reserved,sum(b.paid) AS"
            + " paid,sum(b.fees_earned) AS fees_earned FROM service_payment_accounts a JOIN"
            + " service_account_balances b ON b.id=a.id WHERE "
            + where
            + " GROUP BY a.currency ORDER BY a.currency",
        (r, n) ->
            new ServiceFinanceSummaryResponse(
                r.getString("currency"),
                r.getLong("accounts"),
                r.getBigDecimal("quoted"),
                r.getBigDecimal("received"),
                r.getBigDecimal("refunded"),
                r.getBigDecimal("reserved"),
                r.getBigDecimal("paid"),
                r.getBigDecimal("fees_earned")),
        args);
  }
}
