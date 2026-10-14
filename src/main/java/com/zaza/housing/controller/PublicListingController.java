package com.zaza.housing.controller;

import com.zaza.housing.dto.ListingSearchRequest;
import com.zaza.housing.dto.ListingSearchResponse;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.service.ListingService;
import jakarta.validation.Valid;
import java.util.UUID;
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
    public PublicListingController(ListingService listings) { this.listings = listings; }
    @GetMapping
    public ResponseEntity<ListingSearchResponse> search(@Valid @ModelAttribute ListingSearchRequest query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.search(query));
    }
    @GetMapping("/{id}")
    public ResponseEntity<PublicListingResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.publicListing(id));
    }
}
