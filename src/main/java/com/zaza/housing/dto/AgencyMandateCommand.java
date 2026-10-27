package com.zaza.housing.dto;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record AgencyMandateCommand(@NotNull UUID requestId,@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=2000) String message,@NotNull @AssertTrue Boolean consent,@NotBlank String password) {}
