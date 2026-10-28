package com.zaza.housing.service;

import com.zaza.housing.dto.CaretakerOccupancyResponse;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.InspectionPolicy;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.model.UserAccount;
import com.zaza.housing.repository.AuthRepository;
import com.zaza.housing.repository.InspectionRepository;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.repository.PropertyNoticeRepository;
import com.zaza.housing.repository.PropertyRepository;
import com.zaza.housing.util.Pagination;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CaretakerOccupancyService {
  private final CaretakerAccessService access;
  private final LeaseRepository leases;
  private final LeaseService leaseService;
  private final PropertyRepository inventory;
  private final AuthRepository users;
  private final InspectionRepository inspections;
  private final PropertyNoticeRepository repository;

  public CaretakerOccupancyService(
      CaretakerAccessService access,
      LeaseRepository leases,
      LeaseService leaseService,
      PropertyRepository inventory,
      AuthRepository users,
      InspectionRepository inspections,
      PropertyNoticeRepository repository) {
    this.access = access;
    this.leases = leases;
    this.leaseService = leaseService;
    this.inventory = inventory;
    this.users = users;
    this.inspections = inspections;
    this.repository = repository;
  }

  public RentalLease lease(
      AuthenticatedUser a, UUID o, UUID property, UUID id, String capability, boolean lock) {
    access.property(a, o, property, capability, lock);
    var l = leases.scoped(property, id).orElseThrow(CaretakerAccessService::missing);
    LeaseAccessService.state(
        l, LeaseStatus.SIGNED, LeaseStatus.ACTIVE, LeaseStatus.COMPLETED, LeaseStatus.TERMINATED);
    return l;
  }

  public CaretakerOccupancyResponse get(AuthenticatedUser a, UUID o, UUID p, UUID id) {
    return response(lease(a, o, p, id, "OPERATIONS", false));
  }

  public List<CaretakerOccupancyResponse> list(AuthenticatedUser a, UUID o, UUID p, int l, int f) {
    access.property(a, o, p, "OPERATIONS", false);
    Pagination.validate(l, f);
    return repository.occupancy(p, l, f).stream()
        .map(id -> response(leases.scoped(p, id).orElseThrow()))
        .toList();
  }

  public CaretakerOccupancyResponse response(RentalLease l) {
    var unit = inventory.unit(l.propertyId(), l.unitId()).orElseThrow();
    var rev = leases.revision(l.id(), l.currentRevisionId());
    var policy = inspections.policy(l.id());
    return new CaretakerOccupancyResponse(
        l.id(),
        l.propertyId(),
        l.unitId(),
        unit.label(),
        users.findById(l.tenantId()).map(UserAccount::displayName).orElse("Resident"),
        l.status().name(),
        rev.terms().startDate(),
        leaseService.effectiveEnd(l, rev),
        l.activatedAt(),
        l.unitReleasedAt(),
        policy.isPresent(),
        policy.map(InspectionPolicy::moveInRequired).orElse(false));
  }
}
