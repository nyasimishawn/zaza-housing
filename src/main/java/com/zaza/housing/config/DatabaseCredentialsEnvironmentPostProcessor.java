package com.zaza.housing.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject missing credentials after config/profile loading and before any database connection. */
public class DatabaseCredentialsEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  private static final String MESSAGE =
      "Database password is required. Set ZAZA_DB_PASSWORD in .env or the process environment;"
          + " tests may set ZAZA_TEST_DB_PASSWORD.";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    final String password;
    try {
      password = environment.getProperty("spring.datasource.password");
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      throw new IllegalStateException(MESSAGE);
    }
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(MESSAGE);
    }
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }
}
