package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RentPeriodResponse(
    LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate, BigDecimal amount) {}
