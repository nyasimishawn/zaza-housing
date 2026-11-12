package com.zaza.housing.service;

import com.zaza.housing.dto.LeaseTerms;
import com.zaza.housing.dto.RentPeriodResponse;
import com.zaza.housing.repository.LeaseChangeRepository;
import com.zaza.housing.util.RentSchedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Executed revisions stay immutable; accepted addenda form a prospective rent schedule. */
@Service
public class LeaseScheduleService {
    private final LeaseChangeRepository changes;
    public LeaseScheduleService(LeaseChangeRepository changes) { this.changes=changes; }
    public LocalDate end(UUID lease,LocalDate original) {
        return changes.list(lease,true).stream().map(c->c.details().terms().endDate()).max(LocalDate::compareTo).orElse(original);
    }
    public java.util.NavigableMap<LocalDate,BigDecimal> rates(UUID lease,BigDecimal original) {
        var result=new java.util.TreeMap<LocalDate,BigDecimal>();
        result.put(LocalDate.MIN,original);
        for(var change:changes.list(lease,true)) result.put(change.details().terms().effectiveDate(),change.details().terms().monthlyRent());
        return java.util.Collections.unmodifiableNavigableMap(result);
    }
    public List<RentPeriodResponse> calendar(UUID lease,LeaseTerms original,LocalDate start,LocalDate end) {
        if(ChronoUnit.MONTHS.between(original.startDate().withDayOfMonth(1),end.withDayOfMonth(1))>600)
            throw BillingAccessService.invalid("Billing supports terms of at most 600 calendar months.");
        var rates=rates(lease,original.monthlyRent());
        var points=rates.keySet().stream().filter(d->d.isAfter(start)&&d.isBefore(end)).toList();
        var result=new ArrayList<RentPeriodResponse>();
        LocalDate cursor=start;
        while(cursor.isBefore(end)) {
            LocalDate until=cursor.withDayOfMonth(1).plusMonths(1);
            if(until.isAfter(end)) until=end;
            for(var point:points) if(point.isAfter(cursor)&&point.isBefore(until)) until=point;
            LocalDate due=cursor.withDayOfMonth(original.rentDueDay());
            if(due.isBefore(cursor)) due=cursor;
            result.add(new RentPeriodResponse(cursor,until,due,RentSchedule.amount(rates.floorEntry(cursor).getValue(),cursor,until)));
            cursor=until;
        }
        return List.copyOf(result);
    }
}
