package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record LoginRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 12, max = 72) String password,
    @Size(max = 64) String code,
    @Size(max = 64) String recoveryCode) {
  public LoginRequest(String email, String password) {
    this(email, password, null, null);
  }
}
