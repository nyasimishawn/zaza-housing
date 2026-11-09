package com.zaza.housing.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record KeycloakSettings(
    @Value("${zaza.keycloak.enabled:false}") boolean enabled,
    @Value("${zaza.keycloak.server-url:http://localhost:9080}") String serverUrl,
    @Value("${zaza.keycloak.realm:zaza}") String realm,
    @Value("${zaza.keycloak.client-id:zaza-backend}") String clientId,
    @Value("${zaza.keycloak.client-secret:}") String clientSecret) {
  public KeycloakSettings {
    serverUrl = serverUrl.replaceAll("/+$", "");
    if (enabled) {
      URI uri = URI.create(serverUrl);
      boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
      if (!("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || !realm.matches("[A-Za-z0-9_-]+") || clientId.isBlank() || clientSecret.isBlank()) {
        throw new IllegalArgumentException("Configure a valid Keycloak URL, realm, client ID and secret; HTTPS is required outside localhost.");
      }
    }
  }

  public String issuer() { return serverUrl + "/realms/" + realm; }
  public String oidc(String endpoint) { return issuer() + "/protocol/openid-connect/" + endpoint; }
  public String usersUrl() { return serverUrl + "/admin/realms/" + realm + "/users"; }
}
