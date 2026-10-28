package com.zaza.housing.controller;

import com.zaza.housing.dto.CaretakerOccupancyResponse;
import com.zaza.housing.dto.PropertyNoticeCommand;
import com.zaza.housing.dto.PropertyNoticeCreateRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.PropertyNotice;
import com.zaza.housing.service.CaretakerOccupancyService;
import com.zaza.housing.service.PropertyNoticeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{org}/caretaker/properties/{property}")
public class CaretakerOccupancyController {
  private final CaretakerOccupancyService occupancy;
  private final PropertyNoticeService notices;

  public CaretakerOccupancyController(
      CaretakerOccupancyService occupancy, PropertyNoticeService notices) {
    this.occupancy = occupancy;
    this.notices = notices;
  }

  @GetMapping("/occupancy")
  public List<CaretakerOccupancyResponse> occupancy(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return occupancy.list(a, org, property, limit, offset);
  }

  @GetMapping("/leases/{id}")
  public CaretakerOccupancyResponse lease(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @PathVariable UUID id) {
    return occupancy.get(a, org, property, id);
  }

  @GetMapping("/notices")
  public List<PropertyNotice> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return notices.list(a, org, property, limit, offset);
  }

  @PostMapping("/notices")
  public PropertyNotice create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @RequestBody @Valid PropertyNoticeCreateRequest r) {
    return notices.create(a, org, property, r);
  }

  @GetMapping("/notices/{id}")
  public PropertyNotice get(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @PathVariable UUID id) {
    return notices.get(a, org, property, id, false);
  }

  @GetMapping("/notices/{id}/history")
  public List<CaretakerEvent> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return notices.history(a, org, property, id, limit, offset);
  }

  @PostMapping("/notices/{id}/{command}")
  public PropertyNotice command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @PathVariable UUID id,
      @PathVariable String command,
      @RequestBody @Valid PropertyNoticeCommand r) {
    return notices.command(a, org, property, id, command, r);
  }
}
