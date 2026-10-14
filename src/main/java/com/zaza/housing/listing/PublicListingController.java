package com.zaza.housing.listing;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/listings")
public class PublicListingController {
    private final ListingService listings;
    public PublicListingController(ListingService listings) { this.listings = listings; }
    @GetMapping
    public ResponseEntity<ListingService.SearchResult> search(@Valid @ModelAttribute ListingRequests.Search query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.search(query));
    }
    @GetMapping("/{id}")
    public ResponseEntity<PublicListing> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.publicListing(id));
    }
}
