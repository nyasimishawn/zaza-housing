package com.zaza.housing.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class AgreementHttpResponse {
    private AgreementHttpResponse() { }
    public static ResponseEntity<String> document(String document,String filename) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(new MediaType("text","plain",StandardCharsets.UTF_8))
                .contentLength(document.getBytes(StandardCharsets.UTF_8).length)
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .header("X-Content-Type-Options","nosniff").body(document);
    }
}
