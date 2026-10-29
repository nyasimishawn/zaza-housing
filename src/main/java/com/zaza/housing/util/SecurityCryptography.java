package com.zaza.housing.util;

import com.zaza.housing.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SecurityCryptography {
  private final Map<String, SecretKeySpec> keys;
  private final String active;

  public SecurityCryptography(
      @Value("${zaza.security.keys:${zaza.documents.keys:}}") String configured,
      @Value("${zaza.security.active-key-id:${zaza.documents.active-key-id:}}") String active) {
    var m = new HashMap<String, SecretKeySpec>();
    for (String entry : configured.split(",")) {
      if (entry.isBlank()) continue;
      var p = entry.trim().split(":", 2);
      if (p.length != 2 || !p[0].matches("[A-Za-z0-9_-]{1,40}"))
        throw new IllegalArgumentException("Invalid security key configuration");
      byte[] bytes = Base64.getDecoder().decode(p[1]);
      if (bytes.length != 32 || m.putIfAbsent(p[0], new SecretKeySpec(bytes, "AES")) != null)
        throw new IllegalArgumentException("Invalid security key material");
      Arrays.fill(bytes, (byte) 0);
    }
    this.keys = Map.copyOf(m);
    this.active = active.trim();
    if (!this.active.isEmpty() && !keys.containsKey(this.active))
      throw new IllegalArgumentException("Security key unavailable");
  }

  public boolean configured() {
    return keys.containsKey(active);
  }

  public String encrypt(String context, byte[] bytes) {
    if (!configured()) throw unavailable();
    try {
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, keys.get(active), new GCMParameterSpec(128, nonce));
      c.updateAAD(("ZAZA_SECURITY_V1|" + context + "|" + active).getBytes(StandardCharsets.UTF_8));
      byte[] value = c.doFinal(bytes);
      return active
          + "."
          + Base64.getEncoder()
              .encodeToString(ByteBuffer.allocate(12 + value.length).put(nonce).put(value).array());
    } catch (java.security.GeneralSecurityException e) {
      throw unavailable();
    }
  }

  public byte[] decrypt(String context, String envelope) {
    try {
      var p = envelope.split("\\.", 2);
      byte[] bytes = Base64.getDecoder().decode(p[1]);
      if (bytes.length < 29 || !keys.containsKey(p[0])) throw unavailable();
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE, keys.get(p[0]), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
      c.updateAAD(("ZAZA_SECURITY_V1|" + context + "|" + p[0]).getBytes(StandardCharsets.UTF_8));
      return c.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length));
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Security encryption is unavailable. Contact the operator.");
  }
}
