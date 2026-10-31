package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record VaultDocumentVersion(
    UUID id,
    UUID documentId,
    int number,
    String storageKey,
    String keyId,
    String contentType,
    String contentHash,
    int width,
    int height,
    long byteSize,
    long cipherSize,
    Instant createdAt,
    int pageCount) {
  public VaultDocumentVersion(UUID id, UUID documentId, int number, String storageKey, String keyId,
      String contentType, String contentHash, int width, int height, long byteSize, long cipherSize, Instant createdAt) {
    this(id, documentId, number, storageKey, keyId, contentType, contentHash, width, height, byteSize, cipherSize, createdAt, 1);
  }
}
