package com.zaza.housing.service;

import com.zaza.housing.dto.CaretakerDashboardResponse;
import com.zaza.housing.dto.CaretakerMemberResponse;
import com.zaza.housing.dto.CaretakerPropertyResponse;
import com.zaza.housing.dto.CaretakerUnitResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.util.Pagination;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CaretakerWorkspaceService {
  private final CaretakerAccessService access;
  private final CaretakerRepository repository;

  public CaretakerWorkspaceService(CaretakerAccessService access, CaretakerRepository repository) {
    this.access = access;
    this.repository = repository;
  }

  public CaretakerDashboardResponse dashboard(AuthenticatedUser a, UUID o) {
    var org = access.organization(a, o, false);
    return repository.dashboard(o, a.id(), access.manager(org));
  }

  public CaretakerPropertyResponse property(AuthenticatedUser a, UUID o, UUID id) {
    var p = access.property(a, o, id, "OPERATIONS", false);
    return new CaretakerPropertyResponse(
        p.id(),
        p.name(),
        p.address(),
        p.city(),
        p.countryCode(),
        p.status().name(),
        p.version(),
        repository.capabilities(o, id, a.id()));
  }

  public List<CaretakerPropertyResponse> properties(AuthenticatedUser a, UUID o, int l, int f) {
    access.organization(a, o, false);
    Pagination.validate(l, f);
    return repository.properties(o, a.id(), l, f);
  }

  public List<CaretakerMemberResponse> members(
      AuthenticatedUser a, UUID o, UUID p, boolean eligible, int l, int f) {
    access.requireManager(access.organization(a, o, false));
    if (p != null) access.property(a, o, p, "OPERATIONS", false);
    if (eligible && p == null) throw AgencyAccessService.bad("Choose a property first.");
    Pagination.validate(l, f);
    return repository.members(o, p, eligible, l, f);
  }

  public List<CaretakerUnitResponse> units(AuthenticatedUser a, UUID o, UUID p, int l, int f) {
    access.property(a, o, p, "OPERATIONS", false);
    Pagination.validate(l, f);
    return repository.units(p, l, f);
  }
}
