package com.zaza.housing.util;

import com.zaza.housing.model.ImageContent;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class ImageHttpResponse {
    private ImageHttpResponse() { }
    public static ResponseEntity<Resource> content(ImageContent image) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType())).contentLength(image.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.id() + (image.contentType().equals("image/png") ? ".png\"" : ".jpg\""))
                .header("X-Content-Type-Options", "nosniff").body(image.resource());
    }
}
