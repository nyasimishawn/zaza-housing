package com.zaza.housing.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class DocumentUploadFilterTests {
  @Test
  void boundedUploadsProceedWithoutDiskSpooling() throws Exception {
    assertThat(attempt("/api/v1/me/documents", 100, 12)).isEqualTo(204);
    assertThat(attempt("/api/v1/organizations/o/documents/d/versions", 100, 12)).isEqualTo(204);
  }

  @Test
  void unknownLengthAndOversizedUploadsAreRejectedBeforeMultipartParsing() throws Exception {
    assertThat(attempt("/api/v1/me/documents", -1, 12)).isEqualTo(411);
    assertThat(attempt("/api/v1/me/documents", 12L * 1024 * 1024, 12)).isEqualTo(413);
  }

  @Test
  void unsafeMultipartConfigurationFailsClosedForVaultUploads() throws Exception {
    assertThat(attempt("/api/v1/me/documents", 100, 0)).isEqualTo(503);
  }

  @Test
  void otherMediaRoutesKeepTheirExistingRequestLimits() throws Exception {
    assertThat(attempt("/api/v1/organizations/o/properties/p/videos", 20L * 1024 * 1024, 0))
        .isEqualTo(204);
  }

  @Test
  void capacityIsBoundedAndPermitsAreReleasedAfterCompletion() throws Exception {
    var filter = new DocumentUploadFilter(DataSize.ofMegabytes(12));
    var entered = new java.util.concurrent.CountDownLatch(2);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var tasks = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
    for (int i = 0; i < 2; i++)
      tasks.add(
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try {
                  filter.doFilter(
                      upload(),
                      new MockHttpServletResponse(),
                      (req, res) -> {
                        entered.countDown();
                        try {
                          if (!finish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new java.io.IOException("Timed out");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new java.io.IOException(e);
                        }
                      });
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }));
    try {
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var blocked = new MockHttpServletResponse();
      filter.doFilter(
          upload(),
          blocked,
          (req, res) -> {
            throw new AssertionError("Over-capacity upload reached multipart processing");
          });
      assertThat(blocked.getStatus()).isEqualTo(429);
    } finally {
      finish.countDown();
    }
    for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
    var allowed = new MockHttpServletResponse();
    filter.doFilter(
        upload(),
        allowed,
        (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
    assertThat(allowed.getStatus()).isEqualTo(204);
  }

  @Test
  void alternateMultipartTypesAndUnknownVaultPathsCannotBypassBounds() throws Exception {
    var request =
        new MockHttpServletRequest("POST", "/api/v1/me/documents/invalid/path/") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setServletPath("/api/v1/me/documents/invalid/path/");
    request.setContentType("multipart/mixed; boundary=example");
    var response = new MockHttpServletResponse();
    new DocumentUploadFilter(DataSize.ofMegabytes(12))
        .doFilter(
            request,
            response,
            (req, res) -> {
              throw new AssertionError("Unbounded multipart was parsed");
            });
    assertThat(response.getStatus()).isEqualTo(411);
  }

  private MockHttpServletRequest upload() {
    var r =
        new MockHttpServletRequest("POST", "/api/v1/me/documents") {
          @Override
          public long getContentLengthLong() {
            return 100;
          }
        };
    r.setServletPath("/api/v1/me/documents");
    r.setContentType("multipart/form-data; boundary=example");
    return r;
  }

  private int attempt(String path, long length, long threshold) throws Exception {
    var request =
        new MockHttpServletRequest("POST", path) {
          @Override
          public long getContentLengthLong() {
            return length;
          }
        };
    request.setServletPath(path);
    request.setContentType("multipart/form-data; boundary=example");
    if (length >= 0) request.addHeader("Content-Length", String.valueOf(length));
    var response = new MockHttpServletResponse();
    new DocumentUploadFilter(DataSize.ofMegabytes(threshold))
        .doFilter(
            request,
            response,
            (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
    return response.getStatus();
  }
}
