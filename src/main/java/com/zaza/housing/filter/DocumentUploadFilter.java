package com.zaza.housing.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DocumentUploadFilter extends OncePerRequestFilter {
  private static final long REQUEST_LIMIT = 11L * 1024 * 1024;
  private final boolean memorySafe;
  private final Semaphore uploads = new Semaphore(2, true);

  public DocumentUploadFilter(
      @Value("${spring.servlet.multipart.file-size-threshold:0}") DataSize threshold) {
    this.memorySafe = threshold.toBytes() >= REQUEST_LIMIT;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean upload =
        "POST".equals(request.getMethod())
            && request.getServletPath().matches("/api/v1/(me|organizations/[^/]+)/documents(/.*)?")
            && request.getContentType() != null
            && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    if (upload) {
      int status =
          !memorySafe
              ? 503
              : request.getContentLengthLong() < 0
                  ? 411
                  : request.getContentLengthLong() > REQUEST_LIMIT ? 413 : 0;
      if (status != 0) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response
            .getWriter()
            .write(
                "{\"status\":"
                    + status
                    + ",\"detail\":\"Document uploads require a bounded in-memory multipart request"
                    + " of at most 11 MB.\"}");
        return;
      }
    }
    if (upload) {
      if (!uploads.tryAcquire()) {
        response.setStatus(429);
        response.setHeader("Retry-After", "2");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response
            .getWriter()
            .write(
                "{\"status\":429,\"detail\":\"Document upload capacity is busy. Retry shortly.\"}");
        return;
      }
      try {
        chain.doFilter(request, response);
      } finally {
        uploads.release();
      }
    } else chain.doFilter(request, response);
  }
}
