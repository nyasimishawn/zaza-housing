package com.zaza.housing.service;

import com.zaza.housing.enums.ApiErrorCode;
import com.zaza.housing.config.KeycloakSettings;
import com.zaza.housing.dto.KeycloakTokenResponse;
import com.zaza.housing.exception.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** All Keycloak protocol/admin calls stay on the backend; provider errors never echo secrets. */
@Service
public class KeycloakClientService {
  private final KeycloakSettings settings;
  private final RestClient http;

  public KeycloakClientService(KeycloakSettings settings) {
    this.settings = settings;
    var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  public KeycloakTokenResponse login(String email, String password) {
    return token(Map.of("grant_type", "password", "username", email, "password", password));
  }

  public KeycloakTokenResponse refresh(String refresh) {
    return token(Map.of("grant_type", "refresh_token", "refresh_token", refresh));
  }

  private KeycloakTokenResponse token(Map<String, String> fields) {
    try {
      var result = http.post().uri(settings.oidc("token"))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form(fields))
          .retrieve().body(KeycloakTokenResponse.class);
      if (result == null || result.accessToken() == null || result.refreshToken() == null
          || result.expiresIn() <= 0 || result.refreshExpiresIn() <= 0) throw unavailable();
      return result;
    } catch (RestClientResponseException error) {
      if (error.getStatusCode().value() == 400 || error.getStatusCode().value() == 401)
        throw new ApiException(ApiErrorCode.INVALID_CREDENTIALS, "Credentials or refresh token are invalid, or the identity requires attention in Keycloak.");
      throw unavailable();
    } catch (RestClientException error) { throw unavailable(); }
  }

  private LinkedMultiValueMap<String, String> form(Map<String, String> fields) {
    var form = new LinkedMultiValueMap<String, String>();
    fields.forEach(form::add);
    form.add("client_id", settings.clientId());
    form.add("client_secret", settings.clientSecret());
    return form;
  }

  public void logout(String refresh) {
    try {
      http.post().uri(settings.oidc("logout")).contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form(Map.of("refresh_token", refresh))).retrieve().toBodilessEntity();
    } catch (RestClientResponseException error) {
      // A rejected/expired refresh credential already cannot continue the provider session.
      if (error.getStatusCode().value() != 400) throw unavailable();
    } catch (RestClientException error) { throw unavailable(); }
  }

  private String adminToken() {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = http.post().uri(settings.oidc("token"))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form(Map.of("grant_type", "client_credentials"))).retrieve().body(Map.class);
      if (result == null || !(result.get("access_token") instanceof String value) || value.isBlank())
        throw unavailable();
      return value;
    } catch (RestClientException error) { throw unavailable(); }
  }

  public String createUser(String email, String password, String displayName) {
    int split = displayName.lastIndexOf(' ');
    String first = split < 0 ? displayName : displayName.substring(0, split);
    String last = split < 0 ? "-" : displayName.substring(split + 1);
    try {
      var response = http.post().uri(settings.usersUrl())
          .headers(h -> h.setBearerAuth(adminToken())).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("username", email, "email", email, "firstName", first, "lastName", last,
              "enabled", true, "emailVerified", false,
              "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false))))
          .retrieve().toBodilessEntity();
      var location = response.getHeaders().getLocation();
      if (location == null) throw unavailable();
      String subject = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
      if (!subject.matches("[A-Za-z0-9_-]{1,255}")) throw unavailable();
      return subject;
    } catch (RestClientResponseException error) {
      if (error.getStatusCode().value() == 409)
        throw new ApiException(HttpStatus.CONFLICT, "This identity already exists. Sign in or ask an operator to link an existing account.");
      if (error.getStatusCode().value() == 400)
        throw new ApiException(HttpStatus.BAD_REQUEST, "The identity does not meet the Keycloak profile or password policy.");
      throw unavailable();
    } catch (RestClientException error) { throw unavailable(); }
  }

  public void changePassword(String subject, String password) {
    try {
      http.put().uri(settings.usersUrl() + "/{subject}/reset-password", subject)
          .headers(h -> h.setBearerAuth(adminToken())).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("type", "password", "value", password, "temporary", false))
          .retrieve().toBodilessEntity();
      http.post().uri(settings.usersUrl() + "/{subject}/logout", subject)
          .headers(h -> h.setBearerAuth(adminToken())).retrieve().toBodilessEntity();
    } catch (RestClientResponseException error) {
      if (error.getStatusCode().value() == 400)
        throw new ApiException(HttpStatus.BAD_REQUEST, "The new password does not meet the Keycloak password policy.");
      throw unavailable();
    } catch (RestClientException error) { throw unavailable(); }
  }

  public static ApiException unavailable() {
    return new ApiException(ApiErrorCode.AUTHENTICATION_UNAVAILABLE, "Keycloak authentication is unavailable. Check the local container and backend configuration.");
  }
}
