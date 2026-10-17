package com.zaza.housing.util;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.EncryptedDocument;
import com.zaza.housing.model.VaultDocumentVersion;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class DocumentCryptography {
  private final Map<String, SecretKeySpec> keys;
  private final String activeKeyId;
  private final SecureRandom random = new SecureRandom();

  public DocumentCryptography(
      @Value("${zaza.documents.keys:}") String configuredKeys,
      @Value("${zaza.documents.active-key-id:}") String activeKeyId) {
    var parsed = new HashMap<String, SecretKeySpec>();
    for (String entry : configuredKeys.split(",")) {
      if (entry.isBlank()) continue;
      String[] pair = entry.strip().split(":", 2);
      if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,40}"))
        throw new IllegalArgumentException("Invalid document encryption key configuration.");
      byte[] bytes;
      try {
        bytes = Base64.getDecoder().decode(pair[1]);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid document encryption key configuration.");
      }
      if (bytes.length != 32
          || parsed.putIfAbsent(pair[0], new SecretKeySpec(bytes, "AES")) != null)
        throw new IllegalArgumentException(
            "Document keys must be distinct identifiers with 256-bit material.");
      Arrays.fill(bytes, (byte) 0);
    }
    this.keys = Map.copyOf(parsed);
    this.activeKeyId = activeKeyId.strip();
    if (!this.keys.isEmpty() && !this.keys.containsKey(this.activeKeyId))
      throw new IllegalArgumentException("The active document encryption key is unavailable.");
    if (this.keys.isEmpty() && !this.activeKeyId.isEmpty())
      throw new IllegalArgumentException("Document encryption key material is missing.");
  }

  public boolean configured() {
    return keys.containsKey(activeKeyId);
  }

  public void requireConfigured() {
    if (!configured()) throw unavailable();
  }

  public EncryptedDocument encrypt(
      UUID document, UUID version, String type, String hash, byte[] bytes) {
    requireConfigured();
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(document, version, activeKeyId, type, hash, bytes.length));
      byte[] encrypted = cipher.doFinal(bytes);
      return new EncryptedDocument(
          ByteBuffer.allocate(13 + encrypted.length)
              .put((byte) 1)
              .put(nonce)
              .put(encrypted)
              .array(),
          activeKeyId);
    } catch (GeneralSecurityException e) {
      throw unavailable();
    }
  }

  public byte[] decrypt(VaultDocumentVersion version, byte[] bytes) {
    var key = keys.get(version.keyId());
    if (key == null || bytes.length != version.cipherSize() || bytes.length < 30 || bytes[0] != 1)
      throw unavailable();
    try {
      var buffer = ByteBuffer.wrap(bytes);
      buffer.get();
      byte[] nonce = new byte[12];
      buffer.get(nonce);
      byte[] encrypted = new byte[buffer.remaining()];
      buffer.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(
          aad(
              version.documentId(),
              version.id(),
              version.keyId(),
              version.contentType(),
              version.contentHash(),
              version.byteSize()));
      byte[] result = cipher.doFinal(encrypted);
      if (result.length != version.byteSize()
          || !MessageDigest.isEqual(
              hash(result).getBytes(StandardCharsets.US_ASCII),
              version.contentHash().getBytes(StandardCharsets.US_ASCII))) throw unavailable();
      return result;
    } catch (GeneralSecurityException e) {
      throw unavailable();
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] aad(UUID document, UUID version, String key, String type, String hash, long size) {
    return (document + "|" + version + "|" + key + "|" + type + "|" + hash + "|" + size)
        .getBytes(StandardCharsets.UTF_8);
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Document encryption or content is unavailable. Contact the operator.");
  }
}
