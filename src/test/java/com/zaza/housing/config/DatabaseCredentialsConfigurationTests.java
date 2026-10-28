package com.zaza.housing.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class DatabaseCredentialsConfigurationTests {
  private final DatabaseCredentialsEnvironmentPostProcessor validation =
      new DatabaseCredentialsEnvironmentPostProcessor();

  @Test
  void credentialValidationIsRegisteredForStartup() throws Exception {
    var factories = new java.util.Properties();
    try (var input = new ClassPathResource("META-INF/spring.factories").getInputStream()) {
      factories.load(input);
    }
    assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
        .isEqualTo(DatabaseCredentialsEnvironmentPostProcessor.class.getName());
  }

  private MockEnvironment configuration() throws Exception {
    var environment = new MockEnvironment();
    for (var source :
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yaml"))) {
      environment.getPropertySources().addLast(source);
    }
    return environment;
  }

  @Test
  void mainConfigurationHasNoBuiltInPassword() throws Exception {
    assertThatThrownBy(
            () -> validation.postProcessEnvironment(configuration(), new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Set ZAZA_DB_PASSWORD");
  }

  @Test
  void missingAndBlankPasswordsFailBeforeDatasourceCreation() {
    for (String value : new String[] {"", " ", "\t"}) {
      var environment = new MockEnvironment().withProperty("spring.datasource.password", value);
      assertThatThrownBy(
              () -> validation.postProcessEnvironment(environment, new SpringApplication()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Database password is required");
    }
    assertThatThrownBy(
            () -> validation.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Database password is required");
  }

  @Test
  void explicitPasswordIsResolvedWithoutChangingItsValue() throws Exception {
    var environment = configuration().withProperty("ZAZA_DB_PASSWORD", "test-only-explicit-secret");
    assertThatCode(() -> validation.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
    assertThat(environment.getProperty("spring.datasource.password"))
        .isEqualTo("test-only-explicit-secret");
  }

  @Test
  void testProfileCanSupplyItsOwnPassword() throws Exception {
    var environment = configuration().withProperty("ZAZA_TEST_DB_PASSWORD", "test-only-password");
    var sources =
        new YamlPropertySourceLoader().load("test", new ClassPathResource("application-test.yaml"));
    for (var source : sources) environment.getPropertySources().addFirst(source);
    assertThatCode(() -> validation.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
    assertThat(environment.getProperty("spring.datasource.password"))
        .isEqualTo("test-only-password");
    assertThat(validation.getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
  }
}
