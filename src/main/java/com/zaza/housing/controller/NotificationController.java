package com.zaza.housing.controller;

import com.zaza.housing.dto.NotificationResponse;
import com.zaza.housing.dto.NotificationSummaryResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public List<NotificationResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "false") boolean archived,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return notifications.list(actor, unreadOnly, archived, category, limit, offset);
  }

  @GetMapping("/summary")
  public NotificationSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser actor) {
    return notifications.summary(actor);
  }

  @PatchMapping("/{id}/read")
  public NotificationResponse markRead(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return notifications.markRead(actor, id);
  }
}
