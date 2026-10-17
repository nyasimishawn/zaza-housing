package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.model.VaultDocumentVersion;
import com.zaza.housing.util.DocumentCryptography;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentCryptographyTests {
  private final String first =
      Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
  private final String second =
      Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));

  @Test
  void roundTripUsesDistinctAuthenticatedCiphertextsForTheSamePlaintext() {
    var crypto = new DocumentCryptography("one:" + first, "one");
    byte[] input = "private document scan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID document = UUID.randomUUID(), version = UUID.randomUUID();
    var a = crypto.encrypt(document, version, "image/png", DocumentCryptography.hash(input), input);
    var b = crypto.encrypt(document, version, "image/png", DocumentCryptography.hash(input), input);
    assertThat(a.bytes()).isNotEqualTo(b.bytes());
    assertThat(crypto.decrypt(meta(document, version, "one", input, a.bytes().length), a.bytes()))
        .isEqualTo(input);
  }

  @Test
  void ciphertextIsBoundToDocumentAndVersionIdentity() {
    var crypto = new DocumentCryptography("one:" + first, "one");
    byte[] input = {1, 2, 3};
    UUID d = UUID.randomUUID(), v = UUID.randomUUID();
    var encrypted = crypto.encrypt(d, v, "image/png", DocumentCryptography.hash(input), input);
    assertThatThrownBy(
            () ->
                crypto.decrypt(
                    meta(UUID.randomUUID(), v, "one", input, encrypted.bytes().length),
                    encrypted.bytes()))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
    assertThatThrownBy(
            () ->
                crypto.decrypt(
                    meta(d, UUID.randomUUID(), "one", input, encrypted.bytes().length),
                    encrypted.bytes()))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
  }

  @Test
  void tamperedTagsOrTruncatedPayloadsFailClosed() {
    var crypto = new DocumentCryptography("one:" + first, "one");
    byte[] input = {1, 2, 3};
    UUID d = UUID.randomUUID(), v = UUID.randomUUID();
    var encrypted = crypto.encrypt(d, v, "image/png", DocumentCryptography.hash(input), input);
    var metadata = meta(d, v, "one", input, encrypted.bytes().length);
    byte[] changed = encrypted.bytes().clone();
    changed[changed.length - 1] ^= 1;
    assertThatThrownBy(() -> crypto.decrypt(metadata, changed))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
    assertThatThrownBy(() -> crypto.decrypt(metadata, new byte[0]))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
  }

  @Test
  void keyRotationPreservesOldReadKeysAndUsesTheNewActiveKey() {
    var old = new DocumentCryptography("one:" + first, "one");
    var rotated = new DocumentCryptography("one:" + first + ",two:" + second, "two");
    byte[] input = {1, 2, 3};
    UUID d = UUID.randomUUID(), v = UUID.randomUUID();
    var encrypted = old.encrypt(d, v, "image/png", DocumentCryptography.hash(input), input);
    assertThat(
            rotated.decrypt(meta(d, v, "one", input, encrypted.bytes().length), encrypted.bytes()))
        .isEqualTo(input);
    assertThat(
            rotated
                .encrypt(d, UUID.randomUUID(), "image/png", DocumentCryptography.hash(input), input)
                .keyId())
        .isEqualTo("two");
    var removed = new DocumentCryptography("two:" + second, "two");
    assertThatThrownBy(
            () ->
                removed.decrypt(
                    meta(d, v, "one", input, encrypted.bytes().length), encrypted.bytes()))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
  }

  @Test
  void missingConfigurationNeverFallsBackToPlaintext() {
    var crypto = new DocumentCryptography("", "");
    assertThat(crypto.configured()).isFalse();
    assertThatThrownBy(
            () ->
                crypto.encrypt(
                    UUID.randomUUID(), UUID.randomUUID(), "image/png", "hash", new byte[] {1}))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
  }

  @Test
  void malformedDuplicateOrUnavailableActiveKeysAreRejected() {
    assertThatThrownBy(() -> new DocumentCryptography("one:bad", "one"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DocumentCryptography("one:" + first + ",one:" + second, "one"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DocumentCryptography("one:" + first, "missing"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DocumentCryptography("", "one"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private VaultDocumentVersion meta(UUID d, UUID v, String key, byte[] input, long encryptedSize) {
    return new VaultDocumentVersion(
        v,
        d,
        1,
        "opaque.enc",
        key,
        "image/png",
        DocumentCryptography.hash(input),
        1,
        1,
        input.length,
        encryptedSize,
        Instant.now());
  }
}
