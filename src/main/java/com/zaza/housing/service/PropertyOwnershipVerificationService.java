package com.zaza.housing.service;

import com.zaza.housing.dto.VerificationSummaryResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.ListingRepository;
import com.zaza.housing.repository.VerificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PropertyOwnershipVerificationService {
  private final PropertyService properties;
  private final ListingRepository listings;
  private final VerificationRepository cases;
  private final VerificationAccessService access;

  public PropertyOwnershipVerificationService(
      PropertyService properties,
      ListingRepository listings,
      VerificationRepository cases,
      VerificationAccessService access) {
    this.properties = properties;
    this.listings = listings;
    this.cases = cases;
    this.access = access;
  }

  public VerificationSummaryResponse status(AuthenticatedUser actor, UUID org, UUID property) {
    var p = properties.getForManagement(actor, org, property);
    return summary(p.ownerOrganizationId(), property);
  }

  public VerificationSummaryResponse publicStatus(UUID listing) {
    var source = listings.applicationSource(listing).orElseThrow(DocumentAccessService::missing);
    return summary(cases.propertyOwner(source.propertyId()), source.propertyId());
  }

  private VerificationSummaryResponse summary(UUID org, UUID property) {
    return cases
        .activeProperty(org, property)
        .filter(access::verified)
        .map(
            c ->
                new VerificationSummaryResponse(
                    "VERIFIED", "MANUAL_DOCUMENT_REVIEW", c.validUntil()))
        .orElse(new VerificationSummaryResponse("NOT_VERIFIED", null, null));
  }
}
