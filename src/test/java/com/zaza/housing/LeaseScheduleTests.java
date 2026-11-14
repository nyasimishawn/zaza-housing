package com.zaza.housing;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.model.LeaseChange;
import com.zaza.housing.model.LeaseRevision;
import com.zaza.housing.model.LeaseTermination;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.service.InspectionGuardService;
import com.zaza.housing.repository.LeaseChangeRepository;
import com.zaza.housing.service.LeaseScheduleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LeaseScheduleTests {
    @Test
    void renewedTermMovesTheCheckoutInspectionWindowInsteadOfReusingTheOriginalEnd() {
        UUID id=UUID.randomUUID(),revisionId=UUID.randomUUID();
        var original=terms();
        var lease=new RentalLease(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),LeaseStatus.ACTIVE,1,revisionId,revisionId,0,Instant.EPOCH,Instant.EPOCH,null,null,Instant.EPOCH,Instant.EPOCH);
        var repository=new LeaseRepository(null,null) {
            @Override public LeaseRevision revision(UUID leaseId,UUID revision) { return new LeaseRevision(revisionId,id,1,original,null,"Original document","0".repeat(64),true,Instant.EPOCH); }
            @Override public java.util.Optional<LeaseTermination> openTermination(UUID leaseId) { return java.util.Optional.empty(); }
        };
        var schedule=new LeaseScheduleService(new InMemoryChanges(List.of(change(id,"RENEWAL","2027-04-01","2027-07-01","13000"))));
        var guard=new InspectionGuardService(null,repository,schedule);
        assertThat(guard.withinMoveOutWindow(lease,Instant.parse("2027-03-28T12:00:00Z"))).isFalse();
        assertThat(guard.withinMoveOutWindow(lease,Instant.parse("2027-06-26T12:00:00Z"))).isTrue();
    }
    @Test
    void midMonthAmendmentAndRenewalPreserveHistoricalRatesAndEndExcludedPeriods() {
        UUID lease=UUID.randomUUID();
        var repository=new InMemoryChanges(List.of(change(lease,"AMENDMENT","2027-02-15","2027-04-01","12000"),change(lease,"RENEWAL","2027-04-01","2027-07-01","13000")));
        var schedule=new LeaseScheduleService(repository);
        var terms=terms();
        var periods=schedule.calendar(lease,terms,terms.startDate(),LocalDate.parse("2027-07-01"));
        assertThat(periods).hasSize(7);
        assertThat(periods.get(1).periodStart()).isEqualTo(LocalDate.parse("2027-02-01"));
        assertThat(periods.get(1).periodEnd()).isEqualTo(LocalDate.parse("2027-02-15"));
        assertThat(periods.get(1).amount()).isEqualByComparingTo("5000.00");
        assertThat(periods.get(2).amount()).isEqualByComparingTo("6000.00");
        assertThat(periods.get(2).dueDate()).isEqualTo(LocalDate.parse("2027-02-15"));
        assertThat(periods.getLast().periodEnd()).isEqualTo(LocalDate.parse("2027-07-01"));
        assertThat(periods.stream().map(RentPeriodResponse::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("72000.00");
        assertThat(repository.reads).as("Load accepted rates once for the whole schedule").isEqualTo(1);
        assertThat(schedule.end(lease,terms.endDate())).isEqualTo(LocalDate.parse("2027-07-01"));
    }
    @Test
    void originalScheduleStillWorksWithoutAddendaAndExcessiveTermsAreRejected() {
        var schedule=new LeaseScheduleService(new InMemoryChanges(List.of()));var terms=terms();UUID lease=UUID.randomUUID();
        assertThat(schedule.calendar(lease,terms,terms.startDate(),terms.endDate())).extracting(RentPeriodResponse::amount).containsExactly(new BigDecimal("10000.00"),new BigDecimal("10000.00"),new BigDecimal("10000.00"));
        assertThatThrownBy(()->schedule.calendar(lease,terms,terms.startDate(),terms.startDate().plusMonths(601))).hasMessageContaining("600");
    }
    private LeaseTerms terms() { return new LeaseTerms("Agreement",LocalDate.parse("2027-01-01"),LocalDate.parse("2027-04-01"),new BigDecimal("10000.00"),BigDecimal.ZERO,"KES",5,"Original agreement clauses remain retained."); }
    private LeaseChange change(UUID lease,String kind,String date,String end,String rent) {
        return new LeaseChange(lease,UUID.randomUUID(),UUID.randomUUID(),"Immutable addendum",new LeaseChangeResponse(UUID.randomUUID(),new LeaseChangeTerms(kind,LocalDate.parse(date),LocalDate.parse(end),new BigDecimal(rent),"Agreed prospective rent change."),LeaseParty.LESSOR,"ACCEPTED","0".repeat(64),List.of(),Instant.EPOCH));
    }
    private static class InMemoryChanges extends LeaseChangeRepository {
        private final List<LeaseChange> data;int reads;
        InMemoryChanges(List<LeaseChange> data) { super(null);this.data=data; }
        @Override public List<LeaseChange> list(UUID lease,boolean accepted) { reads++;return data; }
    }
}
