package com.zaza.housing.service;

import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.model.WorkRequest;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WorkAccessService {
  private final WorkRequestRepository work;
  private final PropertyService properties;
  private final ServiceProviderService providers;
  private final OrganizationRepository organizations;
  private final CaretakerAccessService caretakers;

  public WorkAccessService(
      WorkRequestRepository work,
      PropertyService properties,
      ServiceProviderService providers,
      OrganizationRepository organizations,
      CaretakerAccessService caretakers) {
    this.work = work;
    this.properties = properties;
    this.providers = providers;
    this.organizations = organizations;
    this.caretakers = caretakers;
  }

  public WorkAccess get(
      AuthenticatedUser actor,
      WorkParty party,
      UUID organization,
      UUID property,
      UUID id,
      boolean lock) {
    var current = find(id);
    if (party == WorkParty.COORDINATOR) {
      coordinateProperty(actor, organization, property, lock);
      current = find(id);
      if (current.kind() != WorkKind.MAINTENANCE || !current.propertyId().equals(property))
        throw missing("Work request not found.");
      independent(actor, organization, current.tenantId());
    } else if (party == WorkParty.CUSTOMER) {
      if (!current.tenantId().equals(actor.id())) throw missing("Work request not found.");
      if (lock) properties.lockForApplication(current.propertyId());
      current = find(id);
    } else {
      providers.authorize(actor, organization, false);
      if (!organization.equals(current.providerId())) throw missing("Service job not found.");
      if (lock) {
        properties.lockForApplication(current.propertyId());
        providers.authorize(actor, organization, true);
        current = find(id);
      }
      if (!organization.equals(current.providerId())) throw missing("Service job not found.");
    }
    return new WorkAccess(current, party, organization);
  }

  public com.zaza.housing.model.Property coordinateProperty(
      AuthenticatedUser actor, UUID org, UUID property, boolean lock) {
    return caretakers.property(actor, org, property, "MAINTENANCE", lock);
  }

  public void independent(AuthenticatedUser actor, UUID org, UUID tenant) {
    if (actor.id().equals(tenant)
        && !caretakers.manager(caretakers.organization(actor, org, false)))
      throw forbidden("Another caretaker must coordinate your own tenancy work.");
  }

  public void manager(AuthenticatedUser actor, WorkAccess a) {
    caretakers.requireManager(caretakers.organization(actor, a.organizationId(), false));
  }

  public void providerLock(WorkRequest request) {
    if (request.providerId() != null) organizations.lock(request.providerId());
  }

  public WorkRequest find(UUID id) {
    return work.find(id).orElseThrow(() -> missing("Work request not found."));
  }

  public void payer(AuthenticatedUser actor, WorkAccess access) {
    if (access.work().kind() != WorkKind.SERVICE_BOOKING && access.party() == WorkParty.COORDINATOR)
      manager(actor, access);
    if (access.work().kind() == WorkKind.SERVICE_BOOKING) {
      if (access.party() != WorkParty.CUSTOMER)
        throw forbidden("Only the booking customer can authorize the quote.");
    } else if (access.party() != WorkParty.COORDINATOR
        || !access.work().ownerOrganizationId().equals(access.organizationId())
        || access.work().tenantId().equals(actor.id()))
      throw forbidden(
          "Only an owner administrator distinct from the occupant can authorize maintenance"
              + " costs.");
  }

  public static void state(WorkRequest request, WorkStatus... states) {
    for (var state : states) if (request.status() == state) return;
    throw conflict("This action is unavailable while the request is " + request.status() + ".");
  }

  public static void party(WorkAccess access, WorkParty... parties) {
    for (var party : parties) if (access.party() == party) return;
    throw forbidden("This action is unavailable for this participant.");
  }

  public static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException missing(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, message);
  }
}
