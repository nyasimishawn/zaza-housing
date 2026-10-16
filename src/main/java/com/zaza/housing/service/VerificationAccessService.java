package com.zaza.housing.service;

import com.zaza.housing.dto.VerificationDetails;
import com.zaza.housing.enums.DocumentCategory;
import com.zaza.housing.enums.VerificationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.VerificationCase;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.VerificationRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VerificationAccessService {
  private final VerificationRepository cases;
  private final DocumentAccessService access;
  private final DocumentRepository documents;
  private final Clock clock;

  public VerificationAccessService(
      VerificationRepository cases,
      DocumentAccessService access,
      DocumentRepository documents,
      Clock clock) {
    this.cases = cases;
    this.access = access;
    this.documents = documents;
    this.clock = clock;
  }

  public VerificationCase own(AuthenticatedUser actor, UUID org, UUID id, boolean lock) {
    access.owner(actor, org, lock);
    var c = find(id);
    if (!Objects.equals(c.organizationId(), org)
        || (org == null && !actor.id().equals(c.ownerUserId())))
      throw DocumentAccessService.missing();
    return c;
  }

  public VerificationCase find(UUID id) {
    return cases.find(id).orElseThrow(DocumentAccessService::missing);
  }

  public void operator(AuthenticatedUser actor) {
    if (!"PLATFORM_ADMIN".equals(actor.platformRole()))
      throw DocumentAccessService.forbidden("A platform verification operator is required.");
  }

  public VerificationCase operator(
      AuthenticatedUser actor, UUID id, boolean lock, boolean assigned) {
    operator(actor);
    var c = find(id);
    if (lock) {
      access.lockSubject(c.ownerUserId(), c.organizationId());
      c = find(id);
    }
    if (cases.related(actor.id(), id))
      throw DocumentAccessService.forbidden(
          "Verification requires an operator independent of the subject and all submission"
              + " preparers.");
    if (assigned
        && (c.status() != VerificationStatus.UNDER_REVIEW || !actor.id().equals(c.assignedTo())))
      throw DocumentAccessService.missing();
    return c;
  }

  public void version(VerificationCase c, long version) {
    if (c.version() != version)
      throw DocumentAccessService.conflict(
          "The verification case changed. Reload before continuing.");
  }

  public VerificationDetails details(UUID user, UUID org, VerificationDetails details) {
    if (!Set.of(Locale.getISOCountries()).contains(details.countryCode().toUpperCase(Locale.ROOT)))
      throw DocumentAccessService.invalid("Use an ISO two-letter country code.");
    if (new HashSet<>(details.documentVersionIds()).size() != details.documentVersionIds().size())
      throw DocumentAccessService.invalid("Verification evidence versions must be unique.");
    if (org != null
        && !cases.organizationName(org).strip().equalsIgnoreCase(details.claimedName().strip()))
      throw DocumentAccessService.invalid(
          "Business verification must use the organization's current legal name.");
    for (UUID version : details.documentVersionIds()) {
      var d = documents.forVersion(version).orElseThrow(DocumentAccessService::missing);
      if (!Objects.equals(d.organizationId(), org) || !Objects.equals(d.ownerUserId(), user))
        throw DocumentAccessService.missing();
      access.active(d);
      if (!d.retainUntil().isAfter(clock.instant()))
        throw DocumentAccessService.conflict(
            "Extend evidence retention before submitting it for verification.");
      if ((org == null && d.category() != DocumentCategory.IDENTITY)
          || (org != null && d.category() != DocumentCategory.BUSINESS_REGISTRATION))
        throw DocumentAccessService.invalid(
            "Use identity scans for identity verification and business registration scans for"
                + " business verification.");
    }
    return new VerificationDetails(
        details.claimedName().strip(),
        details.countryCode().toUpperCase(Locale.ROOT),
        details.statement().strip(),
        List.copyOf(details.documentVersionIds()));
  }

  public VerificationStatus effective(VerificationCase c) {
    return c.status() == VerificationStatus.VERIFIED && !c.validUntil().isAfter(clock.instant())
        ? VerificationStatus.EXPIRED
        : c.status();
  }

  public boolean verified(VerificationCase c) {
    return effective(c) == VerificationStatus.VERIFIED && cases.currentBusinessName(c);
  }
}
