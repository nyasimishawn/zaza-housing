package com.zaza.housing.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record OwnershipProposalTerms(@NotBlank @Pattern(regexp="COOWNER|TRANSFER") String kind,@NotNull UUID organizationId,
    @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal sharePercent,
    @NotBlank @Size(min=10,max=2000) String reason) { }
