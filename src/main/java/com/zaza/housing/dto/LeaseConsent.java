package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LeaseConsent(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String documentHash,
        @NotBlank @Size(max = 150) String typedName,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotNull @AssertTrue Boolean consent) {
    @Override public String toString() { return "LeaseConsent[documentHash=" + documentHash + ", password=<redacted>]"; }
}
