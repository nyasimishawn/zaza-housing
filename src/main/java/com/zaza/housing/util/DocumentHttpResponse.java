package com.zaza.housing.util;

import com.zaza.housing.model.ImageContent;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class DocumentHttpResponse {
  private DocumentHttpResponse() {}

  public static ResponseEntity<Resource> content(ImageContent content) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.byteSize())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"document-"
                + content.id()
                + (content.contentType().equals("application/pdf") ? ".pdf\"" : content.contentType().equals("image/png") ? ".png\"" : ".jpg\""))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .body(content.resource());
  }
}
