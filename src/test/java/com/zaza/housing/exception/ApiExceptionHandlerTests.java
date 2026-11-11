package com.zaza.housing.exception;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.zaza.housing.enums.ApiErrorCode;
import com.zaza.housing.filter.RequestLoggingFilter;
import com.zaza.housing.util.RequestDiagnostics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.json.JsonMapper;

class ApiExceptionHandlerTests {
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestLoggingFilter()).build();
  }

  @Test void unexpectedAndDatabaseErrorsAreSafeAndCorrelated() throws Exception {
    for (String kind : new String[] {"bug", "database"}) {
      var response = mvc.perform(get("/probe").param("kind", kind)).andReturn().getResponse();
      var body = JsonMapper.builder().build().readTree(response.getContentAsString());
      assertThat(response.getStatus()).isEqualTo(kind.equals("bug") ? 500 : 503);
      assertThat(body.get("code").asText()).isEqualTo(kind.equals("bug") ? "INTERNAL_ERROR" : "DATABASE_UNAVAILABLE");
      assertThat(body.get("requestId").asText()).isEqualTo(response.getHeader(RequestDiagnostics.HEADER));
      assertThat(java.time.Instant.parse(body.get("timestamp").asText())).isNotNull();
      assertThat(response.getContentAsString()).doesNotContain("SECRET", "SELECT", "IllegalStateException", "password");
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
  }

  @Test void applicationCodesKeepTheirExistingStatusAndDetail() throws Exception {
    mvc.perform(get("/probe").param("kind", "stale"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECORD"))
        .andExpect(jsonPath("$.detail").value("Reload this property."));
  }

  @Test void storageCausesAreKeptOutOfControllerErrorResponses() throws Exception {
    mvc.perform(get("/probe").param("kind", "storage"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
        .andExpect(jsonPath("$.detail").value("Storage is temporarily unavailable."))
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IOException"))));
  }

  @Test void malformedJsonAndBeanValidationAreDifferentErrors() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"SECRET_PASSWORD"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET_PASSWORD"))));
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0]").exists());
  }

  @Test void frameworkErrorsAreNotAccidentallyConvertedToInternalErrors() throws Exception {
    mvc.perform(delete("/probe")).andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED")).andExpect(header().exists("Allow"));
    mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("SECRET"))
        .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    mvc.perform(get("/number").param("count", "SECRET"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mvc.perform(get("/probe").param("kind", "upload"))
        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
  }

  @RestController
  static class ProbeController {
    @GetMapping("/probe") String probe(@RequestParam String kind) {
      return switch (kind) {
        case "bug" -> throw new IllegalStateException("SECRET password");
        case "database" -> throw new CannotGetJdbcConnectionException("SELECT SECRET password");
        case "stale" -> throw new ApiException(ApiErrorCode.STALE_RECORD, "Reload this property.");
        case "upload" -> throw new MaxUploadSizeExceededException(10);
        case "storage" -> throw new ApiException(ApiErrorCode.STORAGE_UNAVAILABLE,
            "Storage is temporarily unavailable.", new java.io.IOException("SECRET storage path and password"));
        default -> "ok";
      };
    }
    @PostMapping("/probe") String validate(@Valid @RequestBody ProbeBody body) { return body.name(); }
    @GetMapping("/number") int number(@RequestParam int count) { return count; }
  }
  record ProbeBody(@NotBlank String name) { }
}
