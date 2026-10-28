package com.zaza.housing.controller;

import com.zaza.housing.dto.VerificationSummaryResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.PropertyOwnershipVerificationService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PropertyOwnershipVerificationController {
  private final PropertyOwnershipVerificationService verification;

  public PropertyOwnershipVerificationController(
      PropertyOwnershipVerificationService verification) {
    this.verification = verification;
  }

  @GetMapping(
      "/api/v1/organizations/{organizationId}/properties/{propertyId}/ownership-verification")
  public VerificationSummaryResponse status(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId) {
    return verification.status(actor, organizationId, propertyId);
  }

  @GetMapping("/api/v1/listings/{id}/ownership-verification")
  public ResponseEntity<VerificationSummaryResponse> publicStatus(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(verification.publicStatus(id));
  }
}
