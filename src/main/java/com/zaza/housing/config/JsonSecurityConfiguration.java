package com.zaza.housing.config;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

@Configuration
public class JsonSecurityConfiguration {
  @Bean
  JsonFactoryBuilderCustomizer boundedJsonInput() {
    return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(64).maxStringLength(65536).maxNumberLength(128)
        .maxDocumentLength(1024 * 1024).build());
  }

  @Bean
  JsonMapperBuilderCustomizer numericJsonInput() {
    // Monetary values intentionally support decimal strings. Geographic doubles do not.
    return builder -> {
      for (Class<?> type : new Class<?>[] {Double.class, double.class, Float.class, float.class}) {
        builder.withCoercionConfig(type, config -> config
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
      }
    };
  }
}
