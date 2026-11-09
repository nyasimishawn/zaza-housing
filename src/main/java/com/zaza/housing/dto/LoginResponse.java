package com.zaza.housing.dto;

import com.zaza.housing.model.UserAccount;
import java.time.Instant;

public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserAccount user,
    String refreshToken, Instant refreshExpiresAt) {
  public LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserAccount user) {
    this(accessToken, tokenType, expiresAt, user, null, null);
  }
}
