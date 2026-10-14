package com.zaza.housing.controller;

import com.zaza.housing.dto.ChangeImageStatusRequest;
import com.zaza.housing.dto.PropertyImageResponse;
import com.zaza.housing.dto.UpdateImageRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.PropertyImageService;
import com.zaza.housing.util.ImageHttpResponse;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/images")
public class PropertyImageController {
    private final PropertyImageService images;
    public PropertyImageController(PropertyImageService images) { this.images = images; }
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyImageResponse upload(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @RequestParam MultipartFile file, @RequestParam(defaultValue = "") String altText) {
        return images.upload(actor, organizationId, propertyId, file, altText);
    }
    @GetMapping
    public List<PropertyImageResponse> list(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return images.list(actor, organizationId, propertyId, includeArchived, limit, offset);
    }
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id) {
        return ImageHttpResponse.content(images.privateContent(actor, organizationId, propertyId, id));
    }
    @PutMapping("/{id}")
    public PropertyImageResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody UpdateImageRequest request) {
        return images.update(actor, organizationId, propertyId, id, request);
    }
    @PatchMapping("/{id}/status")
    public PropertyImageResponse status(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody ChangeImageStatusRequest request) {
        return images.changeStatus(actor, organizationId, propertyId, id, request);
    }
}
