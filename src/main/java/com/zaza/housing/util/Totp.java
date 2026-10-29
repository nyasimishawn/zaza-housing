package com.zaza.housing.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 authenticator-compatible HMAC-SHA1, 30-second TOTP. */
public final class Totp {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  public static String generateSecret() {
    byte[] bytes = new byte[20];
    new SecureRandom().nextBytes(bytes);
    StringBuilder out = new StringBuilder();
    int value = 0, bits = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        out.append(ALPHABET.charAt((value >> bits) & 31));
      }
    }
    return out.toString();
  }

  public static String code(String secret, long counter, int digits) {
    try {
      var bytes = new java.io.ByteArrayOutputStream();
      int value = 0, bits = 0;
      for (char c : secret.toCharArray()) {
        int n = ALPHABET.indexOf(c);
        if (n < 0) throw new IllegalArgumentException("Invalid TOTP secret");
        value = (value << 5) | n;
        bits += 5;
        if (bits >= 8) {
          bits -= 8;
          bytes.write((value >> bits) & 255);
        }
      }
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(bytes.toByteArray(), "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
      int o = h[h.length - 1] & 15;
      int n =
          ((h[o] & 127) << 24)
              | ((h[o + 1] & 255) << 16)
              | ((h[o + 2] & 255) << 8)
              | (h[o + 3] & 255);
      int mod = digits == 8 ? 100000000 : 1000000;
      return String.format(java.util.Locale.ROOT, "%0" + digits + "d", n % mod);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static long match(String secret, String supplied, long epochSeconds, long last) {
    if (supplied == null || !supplied.matches("[0-9]{6}")) return -1;
    long found = -1;
    for (long c = epochSeconds / 30 - 1; c <= epochSeconds / 30 + 1; c++) {
      boolean equal =
          MessageDigest.isEqual(
              code(secret, c, 6).getBytes(StandardCharsets.US_ASCII),
              supplied.getBytes(StandardCharsets.US_ASCII));
      if (equal && c > last && c >= 0) found = c;
    }
    return found;
  }
}
