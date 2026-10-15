package com.zaza.housing.controller;

import com.zaza.housing.dto.PublicServiceProviderResponse;
import com.zaza.housing.dto.ServiceOfferResponse;
import com.zaza.housing.dto.ServiceReviewResponse;
import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.service.ServiceProviderService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/service-providers")
public class ServiceDirectoryController {
  private final ServiceProviderService providers;

  public ServiceDirectoryController(ServiceProviderService providers) {
    this.providers = providers;
  }

  @GetMapping
  public List<PublicServiceProviderResponse> list(
      @RequestParam(required = false) ServiceCategory category,
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String city,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return providers.directory(category, country, city, limit, offset);
  }

  @GetMapping("/{id}")
  public PublicServiceProviderResponse get(@PathVariable UUID id) {
    return providers.publicProfile(id);
  }

  @GetMapping("/{id}/offers")
  public List<ServiceOfferResponse> offers(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return providers.publicOffers(id, limit, offset);
  }

  @GetMapping("/{id}/reviews")
  public List<ServiceReviewResponse> reviews(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return providers.reviews(id, limit, offset);
  }
}
