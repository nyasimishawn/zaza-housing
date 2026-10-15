package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.dto.LeaseTerms;
import com.zaza.housing.util.RentSchedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RentScheduleTests {
  private LeaseTerms terms(String start, String end, String monthly) {
    return new LeaseTerms(
        "Rent policy example",
        LocalDate.parse(start),
        LocalDate.parse(end),
        new BigDecimal(monthly),
        BigDecimal.ZERO,
        "KES",
        5,
        "Supplied agreement text for rent calculation tests.");
  }

  @Test
  void leapFebruaryFullMonthHasExactlyTheContractualRent() {
    var terms = terms("2028-02-01", "2028-03-01", "1000");
    var rows = RentSchedule.calendar(terms, terms.endDate());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).amount()).isEqualByComparingTo("1000");
    assertThat(rows.get(0).periodEnd()).isEqualTo(LocalDate.parse("2028-03-01"));
  }

  @Test
  void partialFebruaryUsesTwentyNineDaysAndRoundsHalfUp() {
    assertThat(
            RentSchedule.amount(
                new BigDecimal("1000"),
                LocalDate.parse("2028-02-20"),
                LocalDate.parse("2028-03-01")))
        .isEqualByComparingTo("344.83");
  }

  @Test
  void calendarBoundariesUseEachMonthsOwnDenominator() {
    var terms = terms("2027-01-31", "2027-03-02", "1000");
    var rows = RentSchedule.calendar(terms, terms.endDate());
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).amount()).isEqualByComparingTo("32.26");
    assertThat(rows.get(1).amount()).isEqualByComparingTo("1000");
    assertThat(rows.get(2).amount()).isEqualByComparingTo("32.26");
    assertThat(rows.get(0).dueDate()).isEqualTo(terms.startDate());
  }

  @Test
  void daysAfterAnEarlyEndDoNotAccrueRent() {
    assertThat(
            RentSchedule.amount(
                new BigDecimal("25000"),
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-11")))
        .isEqualByComparingTo("8064.52");
    assertThat(
            RentSchedule.amount(
                new BigDecimal("25000"),
                LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-10-11")))
        .isEqualByComparingTo("0");
  }

  @Test
  void longSchedulesAreBoundedBeforeAllocatingRows() {
    var terms = terms("2026-01-01", "2077-01-01", "1000");
    assertThatThrownBy(() -> RentSchedule.calendar(terms, terms.endDate()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroRentAndEndAtStartProduceNoDebt() {
    var terms = terms("2026-01-01", "2026-02-01", "0");
    assertThat(RentSchedule.calendar(terms, terms.startDate())).isEmpty();
    assertThat(RentSchedule.calendar(terms, terms.endDate()).get(0).amount())
        .isEqualByComparingTo("0");
  }
}
