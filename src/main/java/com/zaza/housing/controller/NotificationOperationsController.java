package com.zaza.housing.controller;

import com.zaza.housing.dto.NotificationQueueHealthResponse;
import com.zaza.housing.service.NotificationDeliveryService;
import org.springframework.web.bind.annotation.*;

/** Access uses the existing platform-administrator and recent MFA filters. */
@RestController
@RequestMapping("/api/v1/admin/operations/notification-deliveries")
public class NotificationOperationsController {
  private final NotificationDeliveryService service;

  public NotificationOperationsController(NotificationDeliveryService service) {
    this.service = service;
  }

  @GetMapping("/summary")
  public NotificationQueueHealthResponse summary() {
    return service.health();
  }
}
