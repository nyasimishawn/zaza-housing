package com.zaza.housing.dto;

import com.zaza.housing.enums.InvoiceKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponse(
    UUID id,
    InvoiceKind kind,
    String description,
    LocalDate dueDate,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal amount,
    BigDecimal credited,
    BigDecimal paid,
    BigDecimal outstanding,
    String status,
    Instant createdAt) {}
