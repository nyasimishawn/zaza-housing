package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.service.AuthService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.hikari.maximum-pool-size=2",
    "spring.datasource.hikari.minimum-idle=1",
    "spring.datasource.hikari.connection-timeout=1000"
})
@ActiveProfiles("test")
class LoginConnectionPoolTests {
  @Autowired AuthService auth;
  @Autowired javax.sql.DataSource dataSource;

  @Test
  void persistentLoginCounterDoesNotRequireANestedPoolConnection() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    String password = "Single-connection-password-42";
    auth.register(email, password, "Pool regression");
    // Flyway needs two connections during startup. Occupy one after migration
    // to leave a single available connection, as concurrent traffic can do.
    try (var occupied = dataSource.getConnection()) {
      var response = auth.login(email, password);
      assertThat(response.user().email()).isEqualTo(email);
      assertThatThrownBy(() -> auth.login(email, "Wrong-single-connection-password"))
          .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status().value()).isEqualTo(401));
    }
  }
}
