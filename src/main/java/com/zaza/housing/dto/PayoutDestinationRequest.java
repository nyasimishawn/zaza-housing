package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record PayoutDestinationRequest(
    @NotNull UUID requestId,
    @NotNull PaymentChannel channel,
    @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{7,119}") String beneficiaryToken,
    @NotBlank @Pattern(regexp = "[A-Za-z ._-]{2,80}[*]{4}[0-9]{2,4}") String maskedLabel,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) {}
