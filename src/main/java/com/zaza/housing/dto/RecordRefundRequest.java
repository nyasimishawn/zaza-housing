package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordRefundRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2)
        BigDecimal amount,
    @NotNull PaymentChannel channel,
    @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._:/-]+") String reference,
    @NotNull LocalDate receivedOn,
    @NotBlank @Size(min = 12, max = 200) String password,
    @NotBlank @Size(max = 2000) String reason) {}
