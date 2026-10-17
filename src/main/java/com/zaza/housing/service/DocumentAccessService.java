package com.zaza.housing.service;

import com.zaza.housing.enums.DocumentStatus;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.VaultDocument;
import com.zaza.housing.model.VaultDocumentVersion;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.OrganizationRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentAccessService {
  private final DocumentRepository documents;
  private final OrganizationRepository organizations;

  public DocumentAccessService(DocumentRepository documents, OrganizationRepository organizations) {
    this.documents = documents;
    this.organizations = organizations;
  }

  public void owner(AuthenticatedUser actor, UUID org, boolean lock) {
    if (lock) lockSubject(org == null ? actor.id() : null, org);
    if (org != null) {
      var membership =
          organizations.findForUser(org, actor.id()).orElseThrow(DocumentAccessService::missing);
      if (membership.role() == MembershipRole.MEMBER)
        throw forbidden("Organization document management requires OWNER or ADMIN membership.");
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lockSubject(UUID user, UUID org) {
    if (org == null) documents.lockPersonal(user);
    else organizations.lock(org);
  }

  public VaultDocument own(AuthenticatedUser actor, UUID org, UUID id, boolean lock) {
    owner(actor, org, lock);
    var d = find(id);
    if (!Objects.equals(d.organizationId(), org)
        || (org == null && !actor.id().equals(d.ownerUserId()))) throw missing();
    return d;
  }

  public VaultDocument find(UUID id) {
    return documents.find(id).orElseThrow(DocumentAccessService::missing);
  }

  public VaultDocumentVersion version(VaultDocument document, UUID version) {
    return documents.version(document.id(), version).orElseThrow(DocumentAccessService::missing);
  }

  public void active(VaultDocument d) {
    if (d.status() != DocumentStatus.ACTIVE)
      throw conflict("This document is archived or being purged.");
  }

  public void version(VaultDocument d, long version) {
    if (d.version() != version) throw conflict("The document changed. Reload before continuing.");
  }

  public static String scope(UUID user, UUID org) {
    return org == null ? "USER:" + user : "ORG:" + org;
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "Document or verification resource not found.");
  }

  public static ApiException invalid(String text) {
    return new ApiException(HttpStatus.BAD_REQUEST, text);
  }

  public static ApiException conflict(String text) {
    return new ApiException(HttpStatus.CONFLICT, text);
  }

  public static ApiException forbidden(String text) {
    return new ApiException(HttpStatus.FORBIDDEN, text);
  }
}
