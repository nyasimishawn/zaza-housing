package com.zaza.housing.util;

import com.zaza.housing.model.VideoContent;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class VideoHttpResponse {
    private VideoHttpResponse() { }
    public static ResponseEntity<Resource> content(VideoContent video) {
        // Spring MVC serves FileSystemResource byte ranges as 206/416 after authorization.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(video.contentType())).contentLength(video.byteSize())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + video.id() + ".mp4\"")
                .header("X-Content-Type-Options", "nosniff").body(video.resource());
    }
}
