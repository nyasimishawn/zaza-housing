package com.zaza.housing.controller;

import com.zaza.housing.dto.ListingSearchRequest;
import com.zaza.housing.dto.ListingSearchResponse;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.service.ListingService;
import com.zaza.housing.service.PropertyImageService;
import com.zaza.housing.service.PropertyVideoService;
import com.zaza.housing.util.ImageHttpResponse;
import com.zaza.housing.util.VideoHttpResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/listings")
public class PublicListingController {
    private final ListingService listings;
    private final PropertyImageService images;
    private final PropertyVideoService videos;
    public PublicListingController(ListingService listings, PropertyImageService images, PropertyVideoService videos) { this.listings = listings; this.images = images; this.videos = videos; }
    @GetMapping
    public ResponseEntity<ListingSearchResponse> search(@Valid @ModelAttribute ListingSearchRequest query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.search(query));
    }
    @GetMapping("/{id}")
    public ResponseEntity<PublicListingResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.publicListing(id));
    }
    @GetMapping("/{id}/images/{imageId}")
    public ResponseEntity<Resource> image(@PathVariable UUID id, @PathVariable UUID imageId) {
        return ImageHttpResponse.content(images.publicContent(listings.publicListing(id), imageId));
    }
    @GetMapping("/{id}/videos/{videoId}")
    public ResponseEntity<Resource> video(@PathVariable UUID id, @PathVariable UUID videoId) {
        return VideoHttpResponse.content(videos.publicContent(listings.publicListing(id), videoId));
    }
    @GetMapping("/{id}/videos/{videoId}/poster")
    public ResponseEntity<Resource> poster(@PathVariable UUID id,@PathVariable UUID videoId) {
        return ImageHttpResponse.content(videos.publicPoster(listings.publicListing(id),videoId));
    }
}
