package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.service.AccountSecurityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountRecoveryController {
  private final AccountSecurityService security;

  public AccountRecoveryController(AccountSecurityService security) {
    this.security = security;
  }

  @PostMapping("/recovery/request")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public SecurityMessageResponse request(@Valid @RequestBody RecoveryEmailRequest r) {
    security.requestRecovery(r.email());
    return new SecurityMessageResponse(
        "If an eligible account exists, recovery instructions will be sent to its email.");
  }

  @PostMapping("/recovery/reset")
  public SecurityMessageResponse reset(@Valid @RequestBody RecoveryResetRequest r) {
    security.reset(r);
    return new SecurityMessageResponse(
        "Password reset. Sign in again; authenticator protection is retained.");
  }

  @PostMapping("/email/verify")
  public SecurityMessageResponse verify(@Valid @RequestBody SecurityTokenRequest r) {
    security.verifyEmail(r.token());
    return new SecurityMessageResponse("Email verified. Return to Account security.");
  }
}
