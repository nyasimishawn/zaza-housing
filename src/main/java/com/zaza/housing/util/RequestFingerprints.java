package com.zaza.housing.util;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

public final class RequestFingerprints {
  private RequestFingerprints() {}

  public static String of(ObjectMapper json, Object body) {
    return AgreementDocuments.hash(
        json.writeValueAsString(canonical(json.convertValue(body, Object.class))));
  }

  private static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach(
          (k, v) -> {
            if (!k.toString().equalsIgnoreCase("password")) sorted.put(k.toString(), canonical(v));
          });
      return sorted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(RequestFingerprints::canonical).toList();
    return value;
  }
}
