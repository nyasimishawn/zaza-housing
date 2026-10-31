package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.NotificationCategory;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.NotificationWorkspaceRepository;
import com.zaza.housing.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationWorkspaceService {
  private final NotificationWorkspaceRepository repository;
  private final NotificationDeliveryTransport transport;
  private final SecurityCryptography crypto;
  private final Clock clock;

  public NotificationWorkspaceService(
      NotificationWorkspaceRepository repository,
      NotificationDeliveryTransport transport,
      SecurityCryptography crypto,
      Clock clock) {
    this.repository = repository;
    this.transport = transport;
    this.crypto = crypto;
    this.clock = clock;
  }

  private ApiException error(HttpStatus status, String message) {
    return new ApiException(status, message);
  }

  @Transactional
  public NotificationSettingsResponse settings(AuthenticatedUser actor) {
    var row = repository.settings(actor.id());
    var phone = repository.phone(actor.id());
    String masked = null;
    if (phone.isPresent() && crypto.configured()) {
      try {
        String value = decode("PHONE|" + actor.id(), (String) phone.get().get("encrypted_phone"));
        masked = "•••• " + value.substring(Math.max(0, value.length() - 4));
      } catch (Exception unavailable) {
        masked = "Unavailable";
      }
    }
    return new NotificationSettingsResponse(
        ((Number) row.get("version")).longValue(),
        (String) row.get("timezone"),
        (Integer) row.get("quiet_start"),
        (Integer) row.get("quiet_end"),
        (String) row.get("email_mode"),
        repository.preferences(actor.id()),
        repository.verified(actor.id()),
        masked,
        phone.isPresent() && phone.get().get("verified_at") != null,
        Map.of(
            "email",
            transport.ready("EMAIL"),
            "sms",
            crypto.configured() && transport.ready("SMS"),
            "push",
            crypto.configured() && transport.ready("PUSH")));
  }

  @Transactional
  public NotificationSettingsResponse save(AuthenticatedUser actor, NotificationSettingsRequest r) {
    repository.lockUser(actor.id());
    var current = repository.settings(actor.id());
    if (((Number) current.get("version")).longValue() != r.version())
      throw error(HttpStatus.CONFLICT, "Preferences changed. Reload before saving.");
    try {
      if (!ZoneId.getAvailableZoneIds().contains(r.timezone()))
        throw new DateTimeException("Unknown timezone");
    } catch (DateTimeException invalid) {
      throw error(HttpStatus.BAD_REQUEST, "Choose a valid IANA timezone, such as Africa/Nairobi.");
    }
    if ((r.quietStart() == null) != (r.quietEnd() == null)
        || (r.quietStart() != null && r.quietStart().equals(r.quietEnd())))
      throw error(HttpStatus.BAD_REQUEST, "Quiet hours need different start and end times.");
    if (r.categories().stream().map(NotificationCategoryPreference::category).distinct().count()
        != NotificationCategory.values().length)
      throw error(HttpStatus.BAD_REQUEST, "Each notification category must appear exactly once.");
    if (r.categories().stream().anyMatch(NotificationCategoryPreference::email)
        && !repository.verified(actor.id()))
      throw error(
          HttpStatus.FORBIDDEN, "Verify your email in Account security to enable email alerts.");
    if (r.categories().stream().anyMatch(NotificationCategoryPreference::sms)
        && repository.phone(actor.id()).map(p -> p.get("verified_at") == null).orElse(true))
      throw error(HttpStatus.FORBIDDEN, "Verify your notification phone before enabling SMS.");
    repository.settings(actor.id(), r);
    return settings(actor);
  }

  public List<NotificationDeviceResponse> devices(AuthenticatedUser actor) {
    return repository.devices(actor.id());
  }

  @Transactional
  public NotificationDeviceResponse device(
      AuthenticatedUser actor, RegisterNotificationDeviceRequest r, String sessionHash) {
    repository.lockUser(actor.id());
    var existing = repository.device(actor.id(), r.installationId());
    UUID id = existing.map(d -> (UUID) d.get("id")).orElseGet(UUID::randomUUID);
    if (existing.isEmpty()
        && repository.devices(actor.id()).stream()
                .filter(NotificationDeviceResponse::active)
                .count()
            >= 10)
      throw error(
          HttpStatus.CONFLICT, "You can enable push on ten devices. Revoke an old device first.");
    UUID session =
        repository
            .session(actor.id(), sessionHash)
            .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "Sign in before enabling push."));
    repository.device(
        id,
        actor.id(),
        session,
        r,
        crypto.encrypt("DEVICE|" + id, r.token().getBytes(StandardCharsets.UTF_8)),
        TokenUtils.hashToken(r.token()),
        clock.instant());
    return repository.devices(actor.id()).stream()
        .filter(d -> d.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void revokeDevice(AuthenticatedUser actor, UUID id) {
    if (!repository.revokeDevice(actor.id(), id, clock.instant()))
      throw error(HttpStatus.NOT_FOUND, "Device not found.");
  }

  @Transactional
  public void challenge(AuthenticatedUser actor, String phone) {
    repository.lockUser(actor.id());
    if (!repository.verified(actor.id()))
      throw error(HttpStatus.FORBIDDEN, "Verify your email before adding an SMS phone.");
    if (!transport.ready("SMS"))
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "SMS delivery is not configured.");
    var current = repository.phone(actor.id());
    if (current.isPresent()
        && ((Timestamp) current.get().get("sent_at"))
            .toInstant()
            .plusSeconds(60)
            .isAfter(clock.instant()))
      throw error(
          HttpStatus.TOO_MANY_REQUESTS,
          "Wait a minute before requesting another verification code.");
    if (repository.phoneChallenges(actor.id(), clock.instant().minusSeconds(86400)) >= 5)
      throw error(
          HttpStatus.TOO_MANY_REQUESTS, "The daily phone verification limit has been reached.");
    String code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    repository.cancelPhoneCodes(actor.id());
    repository.phone(
        actor.id(),
        crypto.encrypt("PHONE|" + actor.id(), phone.getBytes(StandardCharsets.UTF_8)),
        TokenUtils.hashToken(phone),
        TokenUtils.hashToken(actor.id() + "|" + phone + "|" + code),
        clock.instant());
    UUID id = UUID.randomUUID();
    repository.payload(
        id,
        actor.id(),
        "SMS",
        "PHONE_CODE",
        "GENERAL",
        crypto.encrypt(
            "DELIVERY|" + id,
            ("Your Zaza notification phone verification code is "
                    + code
                    + ". It expires in 10 minutes.")
                .getBytes(StandardCharsets.UTF_8)),
        clock.instant(),
        clock.instant().plusSeconds(600));
  }

  @Transactional(noRollbackFor = ApiException.class)
  public void verifyPhone(AuthenticatedUser actor, String code) {
    repository.lockUser(actor.id());
    var phone =
        repository
            .phone(actor.id())
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Request a verification code first."));
    if (((Number) phone.get("attempts")).intValue() >= 5
        || !((Timestamp) phone.get("expires_at")).toInstant().isAfter(clock.instant()))
      throw error(HttpStatus.CONFLICT, "This phone code has expired. Request another code.");
    String number = decode("PHONE|" + actor.id(), (String) phone.get("encrypted_phone"));
    boolean valid =
        MessageDigest.isEqual(
            TokenUtils.hashToken(actor.id() + "|" + number + "|" + code)
                .getBytes(StandardCharsets.UTF_8),
            ((String) phone.get("code_hash")).trim().getBytes(StandardCharsets.UTF_8));
    repository.phoneAttempt(actor.id(), valid, clock.instant());
    if (!valid) throw error(HttpStatus.BAD_REQUEST, "Incorrect phone verification code.");
    repository.cancelPhoneCodes(actor.id());
  }

  @Transactional
  public void removePhone(AuthenticatedUser actor) {
    repository.lockUser(actor.id());
    repository.removePhone(actor.id());
  }

  private String decode(String context, String envelope) {
    return new String(crypto.decrypt(context, envelope), StandardCharsets.UTF_8);
  }

  public List<NotificationDeliveryResponse> deliveries(
      AuthenticatedUser actor, int limit, int offset) {
    Pagination.validate(limit, offset);
    return repository.deliveries(actor.id(), limit, offset);
  }

  public List<NotificationDeliveryAttemptResponse> attempts(AuthenticatedUser actor, UUID id) {
    return repository.attempts(actor.id(), id);
  }

  @Transactional
  public void retry(AuthenticatedUser actor, UUID id) {
    if (!repository.retry(actor.id(), id, clock.instant()))
      throw error(
          HttpStatus.CONFLICT, "This delivery cannot be retried. Reload its current status.");
  }

  @Transactional
  public NotificationInboxResult inbox(
      AuthenticatedUser actor, UUID id, Instant before, String action) {
    if (before != null && before.isAfter(clock.instant().plusSeconds(60)))
      throw error(HttpStatus.BAD_REQUEST, "The inbox cutoff must not be in the future.");
    int count =
        action.equals("restore")
            ? repository.restore(actor.id(), id)
            : repository.inbox(actor.id(), id, before, action);
    if (id != null && count == 0)
      throw error(HttpStatus.NOT_FOUND, "Notification not found or already updated.");
    return new NotificationInboxResult(count);
  }

  public List<NotificationReminderResponse> reminders(
      AuthenticatedUser actor, int limit, int offset) {
    Pagination.validate(limit, offset);
    return repository.reminders(actor.id(), limit, offset);
  }

  public NotificationReminderResponse reminder(AuthenticatedUser actor, UUID id) {
    return repository
        .reminder(actor.id(), id, false)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Reminder not found."));
  }

  private void due(Instant due) {
    if (!due.isAfter(clock.instant()) || due.isAfter(clock.instant().plusSeconds(15552000)))
      throw error(HttpStatus.BAD_REQUEST, "Choose a reminder time within the next 180 days.");
  }

  @Transactional
  public NotificationReminderResponse reminder(
      AuthenticatedUser actor, CreateNotificationReminderRequest r) {
    repository.lockUser(actor.id());
    var old = repository.reminderRequest(actor.id(), r.requestId());
    if (old.isPresent()) {
      var i = old.get();
      if (!i.title().equals(r.title().trim())
          || Duration.between(i.dueAt(), r.dueAt()).abs().compareTo(Duration.ofNanos(1000)) >= 0
          || !i.repeatRule().equals(r.repeatRule()))
        throw error(HttpStatus.CONFLICT, "This reminder request was already used.");
      return i;
    }
    due(r.dueAt());
    if (repository.activeReminders(actor.id()) >= 50)
      throw error(HttpStatus.CONFLICT, "You can have fifty active reminders.");
    UUID id = UUID.randomUUID();
    repository.reminder(id, actor.id(), r, clock.instant());
    return repository.reminder(actor.id(), id, false).orElseThrow();
  }

  private NotificationReminderResponse current(AuthenticatedUser actor, UUID id, long version) {
    var row =
        repository
            .reminder(actor.id(), id, true)
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Reminder not found."));
    if (row.version() != version || !row.status().equals("ACTIVE"))
      throw error(HttpStatus.CONFLICT, "This reminder changed or ended. Reload before continuing.");
    return row;
  }

  @Transactional
  public NotificationReminderResponse updateReminder(
      AuthenticatedUser actor, UUID id, UpdateNotificationReminderRequest r) {
    var previous = current(actor, id, r.version());
    due(r.dueAt());
    if (r.dueAt().isAfter(previous.expiresAt()))
      throw error(
          HttpStatus.BAD_REQUEST,
          "This reminder ends before the selected time. Create a new reminder instead.");
    repository.updateReminder(actor.id(), id, r);
    return repository.reminder(actor.id(), id, false).orElseThrow();
  }

  @Transactional
  public NotificationReminderResponse cancelReminder(
      AuthenticatedUser actor, UUID id, long version) {
    current(actor, id, version);
    repository.cancelReminder(actor.id(), id);
    return repository.reminder(actor.id(), id, false).orElseThrow();
  }

  @Scheduled(fixedDelayString = "${zaza.notifications.reminder-poll-ms:30000}")
  @Transactional
  public void remindersDue() {
    Instant now = clock.instant();
    for (var row : repository.dueReminders(now)) {
      UUID id = (UUID) row.get("id"), user = (UUID) row.get("user_id");
      Instant due = ((Timestamp) row.get("due_at")).toInstant(),
          expires = ((Timestamp) row.get("expires_at")).toInstant();
      if (expires.isAfter(now))
        repository.event(
            user,
            "REMINDER",
            id,
            "REMINDER_DUE",
            (String) row.get("title"),
            "reminder:" + id + ":" + due,
            now);
      String rule = (String) row.get("repeat_rule");
      Instant next = null;
      if (!rule.equals("NONE") && expires.isAfter(now)) {
        long period = rule.equals("DAILY") ? 86400 : 604800;
        next = due.plusSeconds(period * (Duration.between(due, now).getSeconds() / period + 1));
        if (!next.isBefore(expires)) next = null;
      }
      repository.finishReminder(id, next);
    }
  }
}
