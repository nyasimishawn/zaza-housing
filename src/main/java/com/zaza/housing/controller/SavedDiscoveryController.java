package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/discovery")
public class SavedDiscoveryController {
  private final SavedDiscoveryService saved;

  public SavedDiscoveryController(SavedDiscoveryService saved) {
    this.saved = saved;
  }

  private String token(String h) {
    return AccountSecurityService.hash(h.substring(7));
  }

  @GetMapping("/{collection:items|searches|comparisons|alerts}")
  public List<?> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String collection,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String folder) {
    return saved.list(a, token(h), collection, limit, offset, folder);
  }

  @GetMapping("/{collection:items|searches|comparisons|alerts}/{id}")
  public Object get(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String collection,
      @PathVariable UUID id) {
    return saved.read(a, token(h), collection, id);
  }

  @PostMapping("/{collection:items|searches|comparisons}")
  public Object create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String collection,
      @Valid @RequestBody DiscoveryCommandRequest r) {
    return saved.create(a, token(h), collection, r);
  }

  @PutMapping("/{collection:items|searches|comparisons}/{id}")
  public Object edit(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String collection,
      @PathVariable UUID id,
      @Valid @RequestBody DiscoveryCommandRequest r) {
    return saved.update(a, token(h), collection, id, r);
  }

  @PostMapping("/{collection:items|searches|comparisons}/{id}/delete")
  public DiscoveryMessageResponse remove(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String collection,
      @PathVariable UUID id,
      @Valid @RequestBody DiscoveryCommandRequest r) {
    return saved.delete(a, token(h), collection, id, r);
  }

  @PostMapping("/alerts/{id}/read")
  public DiscoveryMessageResponse read(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @Valid @RequestBody DiscoveryCommandRequest r) {
    return saved.markRead(a, token(h), id, r);
  }
}
