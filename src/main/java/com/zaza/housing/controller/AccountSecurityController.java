package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.AccountSecurityService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/security")
public class AccountSecurityController {
  private final AccountSecurityService security;

  public AccountSecurityController(AccountSecurityService security) {
    this.security = security;
  }

  private String token(String authorization) {
    return AccountSecurityService.hash(authorization.substring(7));
  }

  @GetMapping
  public AccountSecurityResponse state(
      @AuthenticationPrincipal AuthenticatedUser a, @RequestHeader("Authorization") String h) {
    return security.state(a, token(h));
  }

  @PostMapping("/mfa/enroll")
  public MfaEnrollmentResponse enroll(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SecurityProofRequest r) {
    return security.enroll(a, token(h), r);
  }

  @PostMapping("/mfa/enable")
  public MfaEnabledResponse enable(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody MfaConfirmRequest r) {
    return security.enable(a, token(h), r);
  }

  @PostMapping("/mfa/verify")
  public AccountSecurityResponse stepUp(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SecurityProofRequest r) {
    return security.stepUp(a, token(h), r);
  }

  @PostMapping("/mfa/disable")
  public AccountSecurityResponse disable(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SecurityProofRequest r) {
    return security.disable(a, token(h), r);
  }

  @PostMapping("/mfa/recovery-codes")
  public MfaEnabledResponse codes(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SecurityProofRequest r) {
    return security.recoveryCodes(a, token(h), r);
  }

  @PostMapping("/password")
  public SecurityMessageResponse password(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SecurityPasswordRequest r) {
    security.changePassword(a, token(h), r);
    return new SecurityMessageResponse("Password changed. Sign in again on your devices.");
  }

  @PostMapping("/sessions/{id}/revoke")
  public SecurityMessageResponse revoke(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    security.revokeSession(a, token(h), id);
    return new SecurityMessageResponse("Selected other session revoked.");
  }

  @PostMapping("/sessions/revoke-others")
  public SecurityMessageResponse others(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SecurityProofRequest r) {
    security.revokeOthers(a, token(h), r);
    return new SecurityMessageResponse("Other sessions revoked.");
  }

  @PostMapping("/email/request")
  public SecurityMessageResponse email(
      @AuthenticationPrincipal AuthenticatedUser a, @RequestHeader("Authorization") String h) {
    security.requestVerification(a, token(h));
    return new SecurityMessageResponse(
        "A verification message has been queued for your account email.");
  }
}
