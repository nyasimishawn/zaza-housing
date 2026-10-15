package com.zaza.housing.util;

import com.zaza.housing.dto.LeaseTerms;
import com.zaza.housing.dto.RentPeriodResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class RentSchedule {
  private RentSchedule() {}

  public static List<RentPeriodResponse> calendar(LeaseTerms terms, LocalDate end) {
    var result = new ArrayList<RentPeriodResponse>();
    LocalDate start = terms.startDate();
    if (ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1)) > 600)
      throw new IllegalArgumentException("Billing supports terms of at most 600 calendar months.");
    while (start.isBefore(end)) {
      LocalDate until = start.withDayOfMonth(1).plusMonths(1);
      if (until.isAfter(end)) until = end;
      BigDecimal amount = amount(terms.monthlyRent(), start, until);
      LocalDate due = start.withDayOfMonth(terms.rentDueDay());
      if (due.isBefore(start)) due = start;
      result.add(new RentPeriodResponse(start, until, due, amount));
      start = until;
    }
    return List.copyOf(result);
  }

  public static BigDecimal amount(BigDecimal monthly, LocalDate start, LocalDate end) {
    if (!end.isAfter(start)) return BigDecimal.ZERO.setScale(2);
    return monthly
        .multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end)))
        .divide(BigDecimal.valueOf(start.lengthOfMonth()), 2, RoundingMode.HALF_UP);
  }
}
