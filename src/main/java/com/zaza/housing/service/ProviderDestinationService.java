package com.zaza.housing.service;

import com.zaza.housing.dto.PayoutDestinationRequest;
import com.zaza.housing.dto.PayoutDestinationResponse;
import com.zaza.housing.dto.ReviewPayoutDestinationRequest;
import com.zaza.housing.enums.PaymentChannel;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.ProviderPayoutRepository;
import com.zaza.housing.util.Pagination;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderDestinationService {
  private final ProviderPayoutRepository payouts;
  private final ServiceProviderService providers;
  private final ServiceFinanceAccessService access;
  private final OrganizationRepository organizations;
  private final ServiceFinanceActivityService activity;
  private final AuditService audit;
  private final AuthService auth;

  public ProviderDestinationService(
      ProviderPayoutRepository payouts,
      ServiceProviderService providers,
      ServiceFinanceAccessService access,
      OrganizationRepository organizations,
      ServiceFinanceActivityService activity,
      AuditService audit,
      AuthService auth) {
    this.payouts = payouts;
    this.providers = providers;
    this.access = access;
    this.organizations = organizations;
    this.activity = activity;
    this.audit = audit;
    this.auth = auth;
  }

  public List<PayoutDestinationResponse> list(
      AuthenticatedUser actor, UUID provider, int limit, int offset) {
    Pagination.validate(limit, offset);
    if (!access.operator(actor)) providers.authorize(actor, provider, false);
    return payouts.destinations(provider, limit, offset);
  }

  @Transactional
  public PayoutDestinationResponse create(
      AuthenticatedUser actor, UUID org, PayoutDestinationRequest r) {
    providers.authorize(actor, org, true);
    var replay = activity.replay(actor, org, r.requestId(), "DESTINATION_REGISTERED", r);
    if (replay.isPresent()) return payouts.destination(replay.get()).orElseThrow();
    if (r.channel() != PaymentChannel.BANK_TRANSFER && r.channel() != PaymentChannel.MOBILE_MONEY)
      throw WorkAccessService.invalid(
          "Provider payouts require a bank or mobile money beneficiary.");
    String currency = r.currency().toUpperCase(Locale.ROOT);
    ServiceProviderService.currency(currency);
    UUID id = payouts.destination(org, actor.id(), r, currency);
    activity.operation(actor, org, r.requestId(), "DESTINATION_REGISTERED", r, id);
    audit.record(actor.id(), org, "PROVIDER_DESTINATION_REGISTERED", id);
    return payouts.destination(id).orElseThrow();
  }

  @Transactional
  public PayoutDestinationResponse review(
      AuthenticatedUser actor, UUID id, ReviewPayoutDestinationRequest r) {
    access.operatorOnly(actor);
    var d =
        payouts
            .destination(id)
            .orElseThrow(() -> WorkAccessService.missing("Payout destination not found."));
    organizations.lock(d.providerId());
    d = payouts.destination(id).orElseThrow();
    if (organizations.member(d.providerId(), actor.id()).isPresent())
      throw WorkAccessService.forbidden(
          "An independent platform operator must verify beneficiary ownership outside this"
              + " application.");
    auth.reauthenticate(actor, r.password());
    if (activity.replay(actor, id, r.requestId(), "DESTINATION_" + r.status(), r).isPresent())
      return d;
    if (d.status().equals(r.status()))
      throw WorkAccessService.conflict("The destination already has this status.");
    payouts.destinationDecision(id, actor.id(), r);
    activity.operation(actor, id, r.requestId(), "DESTINATION_" + r.status(), r, id);
    audit.record(actor.id(), d.providerId(), "PROVIDER_DESTINATION_" + r.status(), id);
    return payouts.destination(id).orElseThrow();
  }
}
