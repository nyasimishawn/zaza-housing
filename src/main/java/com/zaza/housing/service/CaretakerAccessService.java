package com.zaza.housing.service;

import com.zaza.housing.dto.CaretakerMemberResponse;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerGrant;
import com.zaza.housing.model.Organization;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyTask;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.PropertyRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CaretakerAccessService {
  private final OrganizationService organizations;
  private final OrganizationRepository memberships;
  private final PropertyService properties;
  private final PropertyRepository inventory;
  private final CaretakerRepository repository;

  public CaretakerAccessService(
      OrganizationService organizations,
      OrganizationRepository memberships,
      PropertyService properties,
      PropertyRepository inventory,
      CaretakerRepository repository) {
    this.organizations = organizations;
    this.memberships = memberships;
    this.properties = properties;
    this.inventory = inventory;
    this.repository = repository;
  }

  public Organization organization(AuthenticatedUser a, UUID o, boolean write) {
    if (write) memberships.lock(o);
    var org = organizations.get(a, o);
    if (org.type() == OrganizationType.SERVICE_PROVIDER)
      throw AgencyAccessService.bad("Choose a landlord or agency workspace.");
    return org;
  }

  public boolean manager(Organization o) {
    return o.role() != MembershipRole.MEMBER;
  }

  public void requireManager(Organization o) {
    if (!manager(o))
      throw WorkAccessService.forbidden("Owner or administrator permission is required.");
  }

  public Property property(AuthenticatedUser a, UUID o, UUID p, String capability, boolean write) {
    organization(a, o, write);
    if (write) properties.lockForApplication(p);
    if (!repository.allowed(o, p, a.id(), capability)) throw missing();
    return inventory.findInOrganization(o, p, false).orElseThrow(CaretakerAccessService::missing);
  }

  public Property propertyForInvitation(AuthenticatedUser a, UUID o, UUID p) {
    organization(a, o, false);
    return inventory.findInOrganization(o, p, false).orElseThrow(CaretakerAccessService::missing);
  }

  public void active(Property p) {
    if (p.status() != PropertyStatus.ACTIVE)
      throw AgencyAccessService.conflict("Restore the property before continuing operations.");
  }

  public PropertyTask task(AuthenticatedUser a, UUID o, UUID id, boolean write) {
    organization(a, o, write);
    var t = repository.task(o, a.id(), id, false).orElseThrow(CaretakerAccessService::missing);
    property(a, o, t.propertyId(), "OPERATIONS", write);
    return repository.task(o, a.id(), id, write).orElseThrow(CaretakerAccessService::missing);
  }

  public CaretakerGrant grant(AuthenticatedUser a, UUID o, UUID id, boolean write) {
    var org = organization(a, o, write);
    var g = repository.grant(id, false).orElseThrow(CaretakerAccessService::missing);
    if (write) properties.lockForApplication(g.propertyId());
    g = repository.grant(id, write).orElseThrow(CaretakerAccessService::missing);
    boolean same = g.organizationId().equals(o);
    boolean owner =
        inventory
            .findInOrganization(o, g.propertyId(), false)
            .filter(p -> p.ownerOrganizationId().equals(o))
            .isPresent();
    if (!(same && (g.userId().equals(a.id()) || manager(org))) && !(owner && manager(org)))
      throw missing();
    return g;
  }

  public CaretakerMemberResponse assignee(AuthenticatedUser a, UUID o, UUID p, UUID user) {
    var org = organization(a, o, false);
    if (!manager(org) && !a.id().equals(user))
      throw WorkAccessService.forbidden("You can assign your own tasks only.");
    if (!repository.allowed(o, p, user, "OPERATIONS"))
      throw AgencyAccessService.bad(
          "Choose a current member with accepted operations access to this property.");
    var m = memberships.member(o, user).orElseThrow(CaretakerAccessService::missing);
    return new CaretakerMemberResponse(m.userId(), m.displayName(), m.role().name());
  }

  public static com.zaza.housing.exception.ApiException missing() {
    return WorkAccessService.missing("Property operations record not found.");
  }
}
