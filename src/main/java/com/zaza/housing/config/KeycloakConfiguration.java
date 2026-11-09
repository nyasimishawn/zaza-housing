package com.zaza.housing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class KeycloakConfiguration {
  @Bean
  @ConditionalOnProperty(name = "zaza.keycloak.enabled", havingValue = "true")
  JwtDecoder keycloakJwtDecoder(KeycloakSettings settings) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(settings.oidc("certs")).build();
    OAuth2TokenValidator<Jwt> audience = jwt ->
        jwt.getAudience().contains(settings.clientId())
            && settings.clientId().equals(jwt.getClaimAsString("azp"))
            && "Bearer".equals(jwt.getClaimAsString("typ"))
            && jwt.getSubject() != null && !jwt.getSubject().isBlank()
            && jwt.getExpiresAt() != null
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(settings.issuer()), audience));
    return decoder;
  }
}
