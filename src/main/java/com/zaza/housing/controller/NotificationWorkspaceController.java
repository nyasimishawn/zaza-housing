package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.NotificationWorkspaceService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationWorkspaceController {
  private final NotificationWorkspaceService service;

  public NotificationWorkspaceController(NotificationWorkspaceService service) {
    this.service = service;
  }

  @GetMapping("/settings")
  public NotificationSettingsResponse settings(@AuthenticationPrincipal AuthenticatedUser a) {
    return service.settings(a);
  }

  @PutMapping("/settings")
  public NotificationSettingsResponse save(
      @AuthenticationPrincipal AuthenticatedUser a,
      @Valid @RequestBody NotificationSettingsRequest r) {
    return service.save(a, r);
  }

  @GetMapping("/devices")
  public List<NotificationDeviceResponse> devices(@AuthenticationPrincipal AuthenticatedUser a) {
    return service.devices(a);
  }

  @PostMapping("/devices")
  public NotificationDeviceResponse device(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String authorization,
      @Valid @RequestBody RegisterNotificationDeviceRequest r) {
    return service.device(
        a, r, com.zaza.housing.util.TokenUtils.hashToken(authorization.substring(7)));
  }

  @DeleteMapping("/devices/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    service.revokeDevice(a, id);
  }

  @PostMapping("/phone/challenge")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void challenge(
      @AuthenticationPrincipal AuthenticatedUser a, @Valid @RequestBody PhoneChallengeRequest r) {
    service.challenge(a, r.phone());
  }

  @PostMapping("/phone/verify")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(
      @AuthenticationPrincipal AuthenticatedUser a,
      @Valid @RequestBody VerifyNotificationPhoneRequest r) {
    service.verifyPhone(a, r.code());
  }

  @DeleteMapping("/phone")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removePhone(@AuthenticationPrincipal AuthenticatedUser a) {
    service.removePhone(a);
  }

  @GetMapping("/deliveries")
  public List<NotificationDeliveryResponse> deliveries(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.deliveries(a, limit, offset);
  }

  @GetMapping("/deliveries/{id}/attempts")
  public List<NotificationDeliveryAttemptResponse> attempts(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    return service.attempts(a, id);
  }

  @PostMapping("/deliveries/{id}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void retry(@AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    service.retry(a, id);
  }

  @PostMapping("/read-all")
  public NotificationInboxResult readAll(
      @AuthenticationPrincipal AuthenticatedUser a,
      @Valid @RequestBody NotificationInboxCommand r) {
    return service.inbox(a, null, r.before(), "read");
  }

  @PostMapping("/archive-all")
  public NotificationInboxResult archiveAll(
      @AuthenticationPrincipal AuthenticatedUser a,
      @Valid @RequestBody NotificationInboxCommand r) {
    return service.inbox(a, null, r.before(), "archive");
  }

  @PostMapping("/{id}/archive")
  public NotificationInboxResult archive(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    return service.inbox(a, id, null, "archive");
  }

  @PostMapping("/{id}/restore")
  public NotificationInboxResult restore(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    return service.inbox(a, id, null, "restore");
  }

  @GetMapping("/reminders")
  public List<NotificationReminderResponse> reminders(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.reminders(a, limit, offset);
  }

  @PostMapping("/reminders")
  @ResponseStatus(HttpStatus.CREATED)
  public NotificationReminderResponse reminder(
      @AuthenticationPrincipal AuthenticatedUser a,
      @Valid @RequestBody CreateNotificationReminderRequest r) {
    return service.reminder(a, r);
  }

  @GetMapping("/reminders/{id}")
  public NotificationReminderResponse reminderDetail(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    return service.reminder(a, id);
  }

  @PutMapping("/reminders/{id}")
  public NotificationReminderResponse updateReminder(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateNotificationReminderRequest r) {
    return service.updateReminder(a, id, r);
  }

  @PostMapping("/reminders/{id}/cancel")
  public NotificationReminderResponse cancelReminder(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID id,
      @Valid @RequestBody InvitationCommand r) {
    return service.cancelReminder(a, id, r.version());
  }
}
