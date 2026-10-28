package com.zaza.housing.service;

import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AgencyLead;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Organization;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.repository.OrganizationRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgencyAccessService {
  private final OrganizationService organizations;
  private final OrganizationRepository memberships;
  private final AgencyRepository repository;
  private final PropertyService properties;

  public AgencyAccessService(
      OrganizationService organizations,
      OrganizationRepository memberships,
      AgencyRepository repository,
      PropertyService properties) {
    this.organizations = organizations;
    this.memberships = memberships;
    this.repository = repository;
    this.properties = properties;
  }

  public Organization get(AuthenticatedUser actor, UUID org, boolean write, boolean manager) {
    if (write) memberships.lock(org);
    var o = organizations.get(actor, org);
    if (o.type() != OrganizationType.AGENCY) throw bad("Choose an agency workspace.");
    if (manager) manager(o);
    return o;
  }

  public boolean manages(Organization o) {
    return o.role() != MembershipRole.MEMBER;
  }

  public void manager(Organization o) {
    if (!manages(o))
      throw new ApiException(HttpStatus.FORBIDDEN, "Agency management permission is required.");
  }

  public void agent(UUID org, UUID agent) {
    if (agent != null && !repository.currentAgent(org, agent))
      throw bad("Choose a current agency member.");
  }

  public void property(
      AuthenticatedUser actor, UUID org, UUID property, UUID agent, boolean write) {
    if (property == null) return;
    if (write) properties.lockForApplication(property);
    // A manager must currently control the property; agent grants are checked separately.
    var o = get(actor, org, false, false);
    if (manages(o)) properties.getForManagement(actor, org, property);
    else if (!repository.assigned(org, property, actor.id())) throw missing();
    if (agent != null && !repository.assigned(org, property, agent))
      throw bad("Assign this property to the agent first.");
  }

  public AgencyLead lead(AuthenticatedUser actor, UUID org, UUID id, boolean write) {
    var l = repository.lead(org, actor.id(), id, false).orElseThrow(AgencyAccessService::missing);
    if (write && l.propertyId() != null) properties.lockForApplication(l.propertyId());
    return repository.lead(org, actor.id(), id, write).orElseThrow(AgencyAccessService::missing);
  }

  public static void version(long actual, Long expected) {
    if (expected == null || actual != expected)
      throw conflict("The record changed. Reload before continuing.");
  }

  public static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "Agency record not found.");
  }
}
