package com.zaza.housing.dto;

public record MfaEnabledResponse(
    AccountSecurityResponse security, java.util.List<String> recoveryCodes) {}
