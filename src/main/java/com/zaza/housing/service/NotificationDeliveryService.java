package com.zaza.housing.service;

import com.zaza.housing.repository.NotificationDeliveryRepository;
import com.zaza.housing.util.SecurityCryptography;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationDeliveryService {
  private final NotificationDeliveryRepository repository;
  private final NotificationDeliveryTransport transport;
  private final SecurityCryptography crypto;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public NotificationDeliveryService(
      NotificationDeliveryRepository repository,
      NotificationDeliveryTransport transport,
      SecurityCryptography crypto,
      Clock clock,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.transport = transport;
    this.crypto = crypto;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelayString = "${zaza.notifications.poll-ms:30000}")
  public void deliver() {
    for (int i = 0; i < 20; i++)
      if (!Boolean.TRUE.equals(transactions.execute(status -> one()))) break;
  }

  public com.zaza.housing.dto.NotificationQueueHealthResponse health() {
    return new com.zaza.housing.dto.NotificationQueueHealthResponse(
        repository.queues(),
        Map.of(
            "email",
            transport.ready("EMAIL"),
            "sms",
            transport.ready("SMS") && crypto.configured(),
            "push",
            transport.ready("PUSH") && crypto.configured()));
  }

  private boolean one() {
    var optional = repository.next(clock.instant());
    if (optional.isEmpty()) return false;
    var row = optional.get();
    UUID id = (UUID) row.get("id");
    Instant now = clock.instant();
    int previous = ((Number) row.get("attempts")).intValue();
    String channel = (String) row.get("channel"), purpose = (String) row.get("purpose");
    if (!instant(row, "expires_at").isAfter(now)) {
      repository.finish(id, "EXPIRED", previous, now, now, "EXPIRED", null);
      return true;
    }
    if (purpose.equals("INVITATION")) {
      if (!"PENDING".equals(row.get("invitation_status"))
          || !instant(row, "invitation_expires").isAfter(now)) {
        repository.finish(id, "CANCELLED", previous, now, now, "INVITATION_CLOSED", null);
        return true;
      }
    } else {
      if (!Boolean.TRUE.equals(row.get("user_active"))) {
        repository.finish(id, "CANCELLED", previous, now, now, "ACCOUNT_UNAVAILABLE", null);
        return true;
      }
      if (!purpose.equals("PHONE_CODE")
          && !Boolean.TRUE.equals(
              row.get(
                  purpose.equals("DIGEST")
                      ? "email_any_enabled"
                      : channel.toLowerCase() + "_enabled"))) {
        repository.finish(id, "CANCELLED", previous, now, now, "PREFERENCE_DISABLED", null);
        return true;
      }
      if (channel.equals("EMAIL") && row.get("email_verified_at") == null
          || channel.equals("PUSH") && !Boolean.TRUE.equals(row.get("device_active"))
          || channel.equals("SMS")
              && (row.get("encrypted_phone") == null
                  || !purpose.equals("PHONE_CODE") && row.get("phone_verified") == null)) {
        repository.finish(id, "CANCELLED", previous, now, now, "DESTINATION_UNAVAILABLE", null);
        return true;
      }
    }
    if (!purpose.equals("INVITATION") && !purpose.equals("PHONE_CODE")) {
      Instant end = quietEnd(row, now);
      if (end != null) {
        repository.postpone(id, end, "QUIET_HOURS");
        return true;
      }
    }
    if (!transport.ready(channel)) {
      repository.postpone(id, now.plusSeconds(300), "CHANNEL_UNAVAILABLE");
      return true;
    }
    String recipient, body;
    try {
      recipient =
          switch (channel) {
            case "EMAIL" ->
                (String) row.get(purpose.equals("INVITATION") ? "invitation_email" : "email");
            case "SMS" ->
                decode("PHONE|" + row.get("user_id"), (String) row.get("encrypted_phone"));
            default ->
                decode("DEVICE|" + row.get("device_id"), (String) row.get("encrypted_token"));
          };
      body =
          row.get("encrypted_payload") == null
              ? (purpose.equals("DIGEST")
                  ? "Zaza daily update: You have account activity to review. Open Notifications in"
                      + " Zaza for the current details."
                  : "Zaza: You have a new account update. Open Notifications in Zaza for details.")
              : decode("DELIVERY|" + id, (String) row.get("encrypted_payload"));
    } catch (Exception unavailable) {
      repository.postpone(id, now.plusSeconds(300), "ENCRYPTION_UNAVAILABLE");
      return true;
    }
    var result = transport.send(id, channel, recipient, body, (UUID) row.get("notification_id"));
    int attempt = previous + 1;
    String status = result.status();
    if (!result.terminal() && attempt >= 5) status = "FAILED";
    Instant next = now.plusSeconds(Math.min(3600, 30L * (1L << Math.min(attempt, 7))));
    repository.attempt(id, attempt, status, result.errorCode(), now);
    repository.finish(id, status, attempt, now, next, result.errorCode(), result.reference());
    if ("TOKEN_INVALID".equals(result.errorCode()) && row.get("device_id") != null)
      repository.invalidDevice((UUID) row.get("device_id"));
    return true;
  }

  private String decode(String context, String envelope) {
    return new String(crypto.decrypt(context, envelope), StandardCharsets.UTF_8);
  }

  private Instant instant(Map<String, Object> row, String key) {
    return ((Timestamp) row.get(key)).toInstant();
  }

  /** Uses IANA zone rules, including DST transitions, instead of a fixed UTC offset. */
  public static Instant quietEnd(Map<String, Object> row, Instant now) {
    if (row.get("quiet_start") == null || row.get("quiet_end") == null) return null;
    var zone = ZoneId.of((String) row.get("timezone"));
    var local = now.atZone(zone);
    int minute = local.getHour() * 60 + local.getMinute(),
        start = ((Number) row.get("quiet_start")).intValue(),
        end = ((Number) row.get("quiet_end")).intValue();
    boolean quiet = start < end ? minute >= start && minute < end : minute >= start || minute < end;
    if (!quiet) return null;
    LocalDate date = local.toLocalDate();
    if (start > end && minute >= start) date = date.plusDays(1);
    return date.atTime(end / 60, end % 60).atZone(zone).toInstant();
  }
}
