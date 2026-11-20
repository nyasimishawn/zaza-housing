package com.zaza.housing.dto;

public record RegistrationResponse(String message) {
  public static RegistrationResponse accepted() {
    return new RegistrationResponse(
        "Registration request received. Continue by signing in or using account recovery.");
  }
}
