package com.zaza.housing.config;

import com.zaza.housing.controller.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.ObjectMapper;

@RestControllerAdvice(
    assignableTypes = {
      AdministrationController.class,
      SupportController.class,
      SupportAdminController.class,
      SupportKnowledgeController.class
    })
public class AdministrationProjectionAdvice implements ResponseBodyAdvice<Object> {
  private final ObjectMapper json;

  public AdministrationProjectionAdvice(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public boolean supports(MethodParameter m, Class<? extends HttpMessageConverter<?>> c) {
    return true;
  }

  private String key(String raw) {
    StringBuilder b = new StringBuilder();
    boolean upper = false;
    for (char c : raw.toCharArray()) {
      if (c == '_') upper = true;
      else {
        b.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return b.toString();
  }

  private Object project(Object v) {
    if (v instanceof Timestamp t) return t.toInstant().toString();
    if (v instanceof List<?> l) return l.stream().map(this::project).toList();
    if (v instanceof Map<?, ?> m) {
      var result = new LinkedHashMap<String, Object>();
      m.forEach((k, value) -> result.put(key(k.toString()), project(value)));
      return result;
    }
    if (v != null && v.getClass().getName().equals("org.postgresql.util.PGobject"))
      return json.readValue(v.toString(), Object.class);
    return v;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    response.getHeaders().setCacheControl("no-store");
    return body instanceof byte[] ? body : project(body);
  }
}
