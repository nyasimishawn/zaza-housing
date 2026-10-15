package com.zaza.housing.service;

import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ServiceFinanceAccess;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.ServicePaymentRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ServiceFinanceAccessService {
  private final ServicePaymentRepository payments;
  private final WorkRequestRepository work;
  private final OrganizationRepository organizations;
  private final PropertyService properties;
  private final ServiceProviderService providers;

  public ServiceFinanceAccessService(
      ServicePaymentRepository payments,
      WorkRequestRepository work,
      OrganizationRepository organizations,
      PropertyService properties,
      ServiceProviderService providers) {
    this.payments = payments;
    this.work = work;
    this.organizations = organizations;
    this.properties = properties;
    this.providers = providers;
  }

  public boolean manager(AuthenticatedUser actor, UUID org) {
    return org != null
        && organizations
            .member(org, actor.id())
            .filter(m -> m.role() != MembershipRole.MEMBER)
            .isPresent();
  }

  public void owner(AuthenticatedUser actor, UUID org) {
    if (!manager(actor, org))
      throw WorkAccessService.missing("Service financial records not found.");
  }

  public boolean operator(AuthenticatedUser actor) {
    return "PLATFORM_ADMIN".equals(actor.platformRole());
  }

  public void operatorOnly(AuthenticatedUser actor) {
    if (!operator(actor))
      throw WorkAccessService.forbidden("Platform finance operator permission is required.");
  }

  public ServiceFinanceAccess get(AuthenticatedUser actor, UUID id, boolean lock) {
    var account =
        payments
            .find(id)
            .orElseThrow(() -> WorkAccessService.missing("Service account not found."));
    boolean payer =
        actor.id().equals(account.payerUserId()) || manager(actor, account.payerOrganizationId());
    boolean provider = manager(actor, account.providerId());
    boolean operator = operator(actor);
    if (!payer && !provider && !operator)
      throw WorkAccessService.missing("Service account not found.");
    var current = work.find(account.workId()).orElseThrow();
    if (lock) {
      // Match work management's owner -> property -> provider order. Never lock a provider then a
      // property.
      if (account.payerOrganizationId() != null) organizations.lock(account.payerOrganizationId());
      properties.lockForApplication(current.propertyId());
      organizations.lock(account.providerId());
      account = payments.find(id).orElseThrow();
      current = work.find(account.workId()).orElseThrow();
      payer =
          actor.id().equals(account.payerUserId()) || manager(actor, account.payerOrganizationId());
      provider = manager(actor, account.providerId());
      if (!payer && !provider && !operator)
        throw WorkAccessService.missing("Service account not found.");
    }
    return new ServiceFinanceAccess(account, current, payer, provider, operator);
  }

  public void payer(ServiceFinanceAccess a) {
    if (!a.payer())
      throw WorkAccessService.forbidden("Only the paying party can perform this action.");
  }

  public void provider(AuthenticatedUser actor, ServiceFinanceAccess a) {
    providers.authorize(actor, a.account().providerId(), false);
  }

  public void independentOperator(AuthenticatedUser actor, ServiceFinanceAccess a) {
    operatorOnly(actor);
    if (actor.id().equals(a.work().tenantId())
        || actor.id().equals(a.account().payerUserId())
        || organizations.member(a.account().providerId(), actor.id()).isPresent()
        || (a.account().payerOrganizationId() != null
            && organizations.member(a.account().payerOrganizationId(), actor.id()).isPresent()))
      throw WorkAccessService.forbidden(
          "A finance operator independent of the customer, payer and provider must verify external"
              + " funds.");
  }

  public void privatePayments(ServiceFinanceAccess a) {
    if (!a.payer() && !a.operator())
      throw WorkAccessService.forbidden(
          "Incoming payment evidence is restricted to the payer and platform finance operators.");
  }
}
