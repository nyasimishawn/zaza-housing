package com.zaza.housing.service;

import com.zaza.housing.dto.ProviderProfileDetails;
import com.zaza.housing.dto.ProviderProfileRequest;
import com.zaza.housing.dto.ProviderProfileResponse;
import com.zaza.housing.dto.ProviderStateRequest;
import com.zaza.housing.dto.PublicServiceProviderResponse;
import com.zaza.housing.dto.ServiceArea;
import com.zaza.housing.dto.ServiceOfferDetails;
import com.zaza.housing.dto.ServiceOfferRequest;
import com.zaza.housing.dto.ServiceOfferResponse;
import com.zaza.housing.dto.ServiceOfferStateRequest;
import com.zaza.housing.dto.ServiceReviewResponse;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import com.zaza.housing.enums.ProviderStatus;
import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.ServiceOfferStatus;
import com.zaza.housing.enums.ServicePriceUnit;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Organization;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.ServiceProviderRepository;
import com.zaza.housing.util.Pagination;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceProviderService {
  private final ServiceProviderRepository providers;
  private final OrganizationRepository memberships;
  private final OrganizationService organizations;
  private final AuditService audit;

  public ServiceProviderService(
      ServiceProviderRepository providers,
      OrganizationRepository memberships,
      OrganizationService organizations,
      AuditService audit) {
    this.providers = providers;
    this.memberships = memberships;
    this.organizations = organizations;
    this.audit = audit;
  }

  public Organization authorize(AuthenticatedUser actor, UUID organization, boolean lock) {
    if (lock) memberships.lock(organization);
    var org = organizations.get(actor, organization);
    if (org.type() != OrganizationType.SERVICE_PROVIDER)
      throw WorkAccessService.invalid("Use a SERVICE_PROVIDER organization.");
    if (org.role() == MembershipRole.MEMBER)
      throw WorkAccessService.forbidden("Provider organization management permission is required.");
    return org;
  }

  public ProviderProfileResponse get(AuthenticatedUser actor, UUID organization) {
    authorize(actor, organization, false);
    return profile(organization);
  }

  @Transactional
  public ProviderProfileResponse create(
      AuthenticatedUser actor, UUID organization, ProviderProfileDetails details) {
    authorize(actor, organization, true);
    if (providers.profile(organization).isPresent())
      throw WorkAccessService.conflict("A service profile already exists.");
    providers.create(organization, normalize(details));
    audit.record(actor.id(), organization, "SERVICE_PROFILE_CREATED", organization);
    return profile(organization);
  }

  @Transactional
  public ProviderProfileResponse update(
      AuthenticatedUser actor, UUID organization, ProviderProfileRequest request) {
    authorize(actor, organization, true);
    var previous = profile(organization);
    version(previous.version(), request.version());
    providers.update(organization, normalize(request.details()));
    audit.recordSnapshot(
        actor.id(),
        organization,
        "SERVICE_PROFILE_UPDATED",
        organization,
        previous,
        profile(organization));
    return profile(organization);
  }

  @Transactional
  public ProviderProfileResponse state(
      AuthenticatedUser actor, UUID organization, ProviderStateRequest request) {
    authorize(actor, organization, true);
    var previous = profile(organization);
    version(previous.version(), request.version());
    if (request.status() == ProviderStatus.ACTIVE && !providers.hasActiveOffer(organization))
      throw WorkAccessService.conflict(
          "Activate at least one service offer before publishing the profile.");
    providers.status(organization, request.status());
    audit.recordChange(
        actor.id(),
        organization,
        "SERVICE_PROFILE_STATE",
        organization,
        previous.status().name(),
        request.status().name());
    return profile(organization);
  }

  public List<ServiceOfferResponse> offers(
      AuthenticatedUser actor, UUID organization, int limit, int offset) {
    authorize(actor, organization, false);
    Pagination.validate(limit, offset);
    return providers.offers(organization, false, limit, offset);
  }

  @Transactional
  public ServiceOfferResponse createOffer(
      AuthenticatedUser actor, UUID organization, ServiceOfferDetails details) {
    authorize(actor, organization, true);
    profile(organization);
    UUID id = providers.createOffer(organization, normalize(details));
    audit.record(actor.id(), organization, "SERVICE_OFFER_CREATED", id);
    return offer(id);
  }

  @Transactional
  public ServiceOfferResponse updateOffer(
      AuthenticatedUser actor, UUID organization, UUID id, ServiceOfferRequest request) {
    authorize(actor, organization, true);
    var old = scopedOffer(organization, id);
    version(old.version(), request.version());
    providers.updateOffer(id, normalize(request.details()));
    audit.recordSnapshot(actor.id(), organization, "SERVICE_OFFER_UPDATED", id, old, offer(id));
    return offer(id);
  }

  @Transactional
  public ServiceOfferResponse offerState(
      AuthenticatedUser actor, UUID organization, UUID id, ServiceOfferStateRequest request) {
    authorize(actor, organization, true);
    var old = scopedOffer(organization, id);
    version(old.version(), request.version());
    providers.offerStatus(id, request.status());
    audit.recordChange(
        actor.id(),
        organization,
        "SERVICE_OFFER_STATE",
        id,
        old.status().name(),
        request.status().name());
    return offer(id);
  }

  public ProviderProfileResponse profile(UUID id) {
    return providers
        .profile(id)
        .orElseThrow(() -> WorkAccessService.missing("Provider profile not found."));
  }

  public ServiceOfferResponse offer(UUID id) {
    return providers
        .offer(id)
        .orElseThrow(() -> WorkAccessService.missing("Service offer not found."));
  }

  private ServiceOfferResponse scopedOffer(UUID organization, UUID id) {
    var o = offer(id);
    if (!o.providerId().equals(organization))
      throw WorkAccessService.missing("Service offer not found.");
    return o;
  }

  public PublicServiceProviderResponse publicProfile(UUID id) {
    var p = profile(id);
    if (p.status() != ProviderStatus.ACTIVE || !providers.hasActiveOffer(id))
      throw WorkAccessService.missing("Service provider not found.");
    return new PublicServiceProviderResponse(
        id,
        p.details().displayName(),
        p.details().description(),
        p.details().areas(),
        "NOT_VERIFIED",
        p.reviewCount(),
        p.averageRating());
  }

  public List<PublicServiceProviderResponse> directory(
      ServiceCategory category, String country, String city, int limit, int offset) {
    Pagination.validate(limit, offset);
    if (country != null) {
      country = country.toUpperCase(Locale.ROOT);
      country(country);
    }
    if (city != null && (city.isBlank() || city.length() > 100))
      throw WorkAccessService.invalid("Use a city of 1-100 characters.");
    return providers
        .directory(category, country, city == null ? null : city.trim(), limit, offset)
        .stream()
        .map(
            p ->
                new PublicServiceProviderResponse(
                    p.organizationId(),
                    p.details().displayName(),
                    p.details().description(),
                    p.details().areas(),
                    p.verificationStatus(),
                    p.reviewCount(),
                    p.averageRating()))
        .toList();
  }

  public List<ServiceOfferResponse> publicOffers(UUID id, int limit, int offset) {
    publicProfile(id);
    Pagination.validate(limit, offset);
    return providers.offers(id, true, limit, offset);
  }

  public List<ServiceReviewResponse> reviews(UUID id, int limit, int offset) {
    publicProfile(id);
    Pagination.validate(limit, offset);
    return providers.reviews(id, limit, offset);
  }

  // Call only after acquiring the work property's lock. Provider organization lock serializes
  // dispatch/calendar writers.
  public ServiceOfferResponse dispatch(
      UUID id, long expected, ServiceCategory category, String country, String city) {
    var candidate = offer(id);
    memberships.lock(candidate.providerId());
    var offer = offer(id);
    version(offer.version(), expected);
    var profile = profile(offer.providerId());
    if (profile.status() != ProviderStatus.ACTIVE
        || offer.status() != ServiceOfferStatus.ACTIVE
        || offer.details().category() != category)
      throw WorkAccessService.conflict("Choose an active offer matching the work category.");
    boolean covered =
        profile.details().areas().stream()
            .anyMatch(
                a ->
                    a.countryCode().equals(country)
                        && (a.city().equals("*") || a.city().equalsIgnoreCase(city)));
    if (!covered) throw WorkAccessService.conflict("The provider does not cover this location.");
    return offer;
  }

  private ProviderProfileDetails normalize(ProviderProfileDetails d) {
    var areas =
        d.areas().stream()
            .map(
                a -> {
                  String c = a.countryCode().toUpperCase(Locale.ROOT);
                  country(c);
                  return new ServiceArea(c, a.city().trim());
                })
            .distinct()
            .toList();
    return new ProviderProfileDetails(d.displayName().trim(), d.description().trim(), areas);
  }

  private ServiceOfferDetails normalize(ServiceOfferDetails d) {
    String currency = d.currency().toUpperCase(Locale.ROOT);
    currency(currency);
    if ((d.priceUnit() == ServicePriceUnit.QUOTE_REQUIRED) != (d.amount() == null))
      throw WorkAccessService.invalid(
          "Quote-only offers omit the amount; fixed/hourly offers require a positive amount.");
    return new ServiceOfferDetails(
        d.category(),
        d.title().trim(),
        d.description().trim(),
        d.priceUnit(),
        d.amount() == null ? null : d.amount().setScale(2),
        currency);
  }

  public static void currency(String value) {
    try {
      if (Currency.getInstance(value).getDefaultFractionDigits() != 2)
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw WorkAccessService.invalid("Use an ISO currency with two fractional digits.");
    }
  }

  private static void country(String value) {
    if (!Set.of(Locale.getISOCountries()).contains(value))
      throw WorkAccessService.invalid("Use an ISO country code.");
  }

  private static void version(long current, long expected) {
    if (current != expected)
      throw WorkAccessService.conflict(
          "The provider profile or offer changed. Reload before continuing.");
  }
}
