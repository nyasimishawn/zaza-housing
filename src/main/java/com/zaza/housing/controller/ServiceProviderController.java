package com.zaza.housing.controller;

import com.zaza.housing.dto.ProviderProfileDetails;
import com.zaza.housing.dto.ProviderProfileRequest;
import com.zaza.housing.dto.ProviderProfileResponse;
import com.zaza.housing.dto.ProviderStateRequest;
import com.zaza.housing.dto.ServiceOfferDetails;
import com.zaza.housing.dto.ServiceOfferRequest;
import com.zaza.housing.dto.ServiceOfferResponse;
import com.zaza.housing.dto.ServiceOfferStateRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ServiceProviderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/service-profile")
public class ServiceProviderController {
  private final ServiceProviderService providers;

  public ServiceProviderController(ServiceProviderService providers) {
    this.providers = providers;
  }

  @GetMapping
  public ProviderProfileResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId) {
    return providers.get(actor, organizationId);
  }

  @PostMapping
  public ProviderProfileResponse create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody ProviderProfileDetails request) {
    return providers.create(actor, organizationId, request);
  }

  @PutMapping
  public ProviderProfileResponse update(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody ProviderProfileRequest request) {
    return providers.update(actor, organizationId, request);
  }

  @PostMapping("/state")
  public ProviderProfileResponse state(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody ProviderStateRequest request) {
    return providers.state(actor, organizationId, request);
  }

  @GetMapping("/offers")
  public List<ServiceOfferResponse> offers(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return providers.offers(actor, organizationId, limit, offset);
  }

  @PostMapping("/offers")
  public ServiceOfferResponse createOffer(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody ServiceOfferDetails request) {
    return providers.createOffer(actor, organizationId, request);
  }

  @PutMapping("/offers/{offerId}")
  public ServiceOfferResponse updateOffer(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID offerId,
      @Valid @RequestBody ServiceOfferRequest request) {
    return providers.updateOffer(actor, organizationId, offerId, request);
  }

  @PostMapping("/offers/{offerId}/state")
  public ServiceOfferResponse offerState(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID offerId,
      @Valid @RequestBody ServiceOfferStateRequest request) {
    return providers.offerState(actor, organizationId, offerId, request);
  }
}
