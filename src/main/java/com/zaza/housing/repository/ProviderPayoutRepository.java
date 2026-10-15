package com.zaza.housing.repository;

import com.zaza.housing.dto.PayoutDestinationRequest;
import com.zaza.housing.dto.PayoutDestinationResponse;
import com.zaza.housing.dto.ProviderPayoutResponse;
import com.zaza.housing.dto.ReviewPayoutDestinationRequest;
import com.zaza.housing.enums.PaymentChannel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderPayoutRepository {
  private final JdbcTemplate jdbc;

  public ProviderPayoutRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String DESTINATIONS =
      "SELECT d.*,coalesce((SELECT x.status FROM provider_destination_decisions x WHERE"
          + " x.destination_id=d.id ORDER BY sequence DESC LIMIT 1),'PENDING') AS status FROM"
          + " provider_payout_destinations d";
  private static final RowMapper<PayoutDestinationResponse> DESTINATION =
      (r, n) ->
          new PayoutDestinationResponse(
              r.getObject("id", UUID.class),
              r.getObject("provider_id", UUID.class),
              PaymentChannel.valueOf(r.getString("channel")),
              r.getString("beneficiary_token"),
              r.getString("masked_label"),
              r.getString("currency"),
              r.getString("status"),
              r.getTimestamp("created_at").toInstant());

  public Optional<PayoutDestinationResponse> destination(UUID id) {
    return jdbc.query(DESTINATIONS + " WHERE d.id=?", DESTINATION, id).stream().findFirst();
  }

  public List<PayoutDestinationResponse> destinations(UUID provider, int limit, int offset) {
    return jdbc.query(
        DESTINATIONS + " WHERE d.provider_id=? ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",
        DESTINATION,
        provider,
        limit,
        offset);
  }

  public UUID destination(UUID org, UUID actor, PayoutDestinationRequest r, String currency) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " provider_payout_destinations(id,provider_id,channel,beneficiary_token,masked_label,currency,created_by)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        org,
        r.channel().name(),
        r.beneficiaryToken(),
        r.maskedLabel(),
        currency,
        actor);
    return id;
  }

  public void destinationDecision(UUID id, UUID actor, ReviewPayoutDestinationRequest r) {
    jdbc.update(
        "INSERT INTO provider_destination_decisions(id,destination_id,status,actor_id,reason)"
            + " VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        r.status(),
        actor,
        r.reason().trim());
  }

  public UUID request(UUID account, UUID destination, UUID actor, BigDecimal amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO provider_payout_requests(id,account_id,destination_id,amount,requested_by)"
            + " VALUES (?,?,?,?,?)",
        id,
        account,
        destination,
        amount,
        actor);
    return id;
  }

  private static final RowMapper<ProviderPayoutResponse> PAYOUT =
      (r, n) ->
          new ProviderPayoutResponse(
              r.getObject("id", UUID.class),
              r.getObject("account_id", UUID.class),
              r.getObject("destination_id", UUID.class),
              r.getBigDecimal("amount"),
              r.getString("currency"),
              r.getString("status"),
              r.getString("decision_reason"),
              r.getObject("requested_by", UUID.class),
              r.getObject("approved_by", UUID.class),
              r.getString("reference"),
              r.getObject("paid_on", LocalDate.class),
              r.getTimestamp("created_at").toInstant());

  public Optional<ProviderPayoutResponse> find(UUID account, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM provider_payout_status WHERE account_id=? AND id=?", PAYOUT, account, id)
        .stream()
        .findFirst();
  }

  public List<ProviderPayoutResponse> list(UUID account, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM provider_payout_status WHERE account_id=? ORDER BY created_at DESC,id LIMIT"
            + " ? OFFSET ?",
        PAYOUT,
        account,
        limit,
        offset);
  }

  public List<ProviderPayoutResponse> queue(UUID provider, String status, int limit, int offset) {
    var args = new ArrayList<Object>();
    String where = " WHERE true";
    if (provider != null) {
      where += " AND a.provider_id=?";
      args.add(provider);
    }
    if (status != null) {
      where += " AND p.status=?";
      args.add(status);
    }
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT p.* FROM provider_payout_status p JOIN service_payment_accounts a ON"
            + " a.id=p.account_id"
            + where
            + " ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",
        PAYOUT,
        args.toArray());
  }

  public void decision(
      UUID id,
      UUID actor,
      String status,
      String reason,
      PaymentChannel channel,
      String ref,
      LocalDate date) {
    jdbc.update(
        "INSERT INTO"
            + " provider_payout_decisions(id,payout_id,status,actor_id,reason,channel,reference,paid_on)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        status,
        actor,
        reason.trim(),
        channel == null ? null : channel.name(),
        ref,
        date);
  }
}
