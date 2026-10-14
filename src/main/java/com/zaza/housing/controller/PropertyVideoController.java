package com.zaza.housing.controller;

import com.zaza.housing.dto.ChangeVideoStatusRequest;
import com.zaza.housing.dto.PropertyVideoResponse;
import com.zaza.housing.dto.UpdateVideoRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.PropertyVideoService;
import com.zaza.housing.util.VideoHttpResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/videos")
public class PropertyVideoController {
    private final PropertyVideoService videos;
    public PropertyVideoController(PropertyVideoService videos) { this.videos = videos; }
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyVideoResponse upload(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @RequestParam MultipartFile file, @RequestParam(defaultValue = "") String caption) {
        return videos.upload(actor, organizationId, propertyId, file, caption);
    }
    @GetMapping
    public List<PropertyVideoResponse> list(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return videos.list(actor, organizationId, propertyId, includeArchived, limit, offset);
    }
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id) {
        return VideoHttpResponse.content(videos.privateContent(actor, organizationId, propertyId, id));
    }
    @PutMapping("/{id}")
    public PropertyVideoResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody UpdateVideoRequest request) {
        return videos.update(actor, organizationId, propertyId, id, request);
    }
    @PatchMapping("/{id}/status")
    public PropertyVideoResponse status(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody ChangeVideoStatusRequest request) {
        return videos.changeStatus(actor, organizationId, propertyId, id, request);
    }
}
