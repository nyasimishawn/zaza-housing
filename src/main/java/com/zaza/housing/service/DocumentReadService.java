package com.zaza.housing.service;

import com.zaza.housing.dto.DocumentEventResponse;
import com.zaza.housing.dto.DocumentResponse;
import com.zaza.housing.dto.DocumentStorageStatusResponse;
import com.zaza.housing.dto.DocumentVersionResponse;
import com.zaza.housing.enums.DocumentStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.VaultDocument;
import com.zaza.housing.model.VaultDocumentVersion;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.LocalDocumentStorageRepository;
import com.zaza.housing.util.DocumentCryptography;
import com.zaza.housing.util.Pagination;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentReadService {
  private final DocumentAccessService access;
  private final DocumentRepository documents;
  private final LocalDocumentStorageRepository storage;
  private final DocumentCryptography crypto;
  private final AuditService audit;

  public DocumentReadService(
      DocumentAccessService access,
      DocumentRepository documents,
      LocalDocumentStorageRepository storage,
      DocumentCryptography crypto,
      AuditService audit) {
    this.access = access;
    this.documents = documents;
    this.storage = storage;
    this.crypto = crypto;
    this.audit = audit;
  }

  public DocumentStorageStatusResponse status(AuthenticatedUser actor, UUID org) {
    access.owner(actor, org, false);
    return new DocumentStorageStatusResponse(
        crypto.configured(),
        "JPEG/PNG scans and PDF (up to 20 pages, 10 MB input); PDFs are normalized to raster pages before encryption",
        20,
        "AES-256-GCM");
  }

  public List<DocumentResponse> list(
      AuthenticatedUser actor, UUID org, DocumentStatus status, int limit, int offset) {
    access.owner(actor, org, false);
    Pagination.validate(limit, offset);
    var rows = documents.list(actor.id(), org, status, limit, offset);
    var versions =
        documents.currentVersions(rows.stream().map(VaultDocument::currentVersionId).toList());
    return rows.stream().map(d -> response(d, versions.get(d.currentVersionId()))).toList();
  }

  public List<DocumentResponse> search(AuthenticatedUser actor,UUID org,DocumentStatus status,String query,
      com.zaza.housing.enums.DocumentCategory category,String kind,int limit,int offset){
    access.owner(actor,org,false);Pagination.validate(limit,offset);
    if(query!=null&&query.length()>150||kind!=null&&!List.of("PDF","IMAGE").contains(kind))throw DocumentAccessService.invalid("Choose a valid document search or file type.");
    var rows=documents.search(actor.id(),org,status,query,category,kind,limit,offset);
    var versions=documents.currentVersions(rows.stream().map(VaultDocument::currentVersionId).toList());
    return rows.stream().map(d->response(d,versions.get(d.currentVersionId()))).toList();
  }

  public DocumentResponse get(AuthenticatedUser actor, UUID org, UUID id) {
    return response(access.own(actor, org, id, false));
  }

  public DocumentResponse response(VaultDocument d) {
    return response(d, access.version(d, d.currentVersionId()));
  }

  public DocumentResponse response(VaultDocument d, VaultDocumentVersion v) {
    return new DocumentResponse(
        d.id(),
        d.category(),
        d.title(),
        d.status(),
        d.version(),
        d.retainUntil(),
        response(v),
        d.createdAt(),
        d.updatedAt());
  }

  public DocumentVersionResponse response(VaultDocumentVersion v) {
    return new DocumentVersionResponse(
        v.id(),
        v.number(),
        v.contentType(),
        v.contentHash(),
        v.width(),
        v.height(),
        v.byteSize(),
        v.createdAt(), v.pageCount());
  }

  public List<DocumentVersionResponse> versions(
      AuthenticatedUser actor, UUID org, UUID id, int limit, int offset) {
    access.own(actor, org, id, false);
    Pagination.validate(limit, offset);
    return documents.versions(id, limit, offset).stream().map(this::response).toList();
  }

  public List<DocumentEventResponse> history(
      AuthenticatedUser actor, UUID org, UUID id, int limit, int offset) {
    access.own(actor, org, id, false);
    Pagination.validate(limit, offset);
    return documents.history(id, null, false, limit, offset);
  }

  @Transactional
  public ImageContent content(AuthenticatedUser actor, UUID org, UUID id, UUID version) {
    var d = access.own(actor, org, id, true);
    access.active(d);
    var v = access.version(d, version);
    var result = content(v);
    audit.record(actor.id(), org, "VAULT_DOCUMENT_READ", version);
    return result;
  }

  public ImageContent content(VaultDocumentVersion v) {
    try {
      byte[] bytes = crypto.decrypt(v, storage.read(v.storageKey(), v.cipherSize()));
      return new ImageContent(v.id(), new ByteArrayResource(bytes), v.contentType(), bytes.length);
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "Encrypted document content is unavailable.");
    }
  }
}
