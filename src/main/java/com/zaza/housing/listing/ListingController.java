package com.zaza.housing.listing;

import com.zaza.housing.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.zaza.housing.listing.ListingRequests.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/listings")
public class ListingController {
    private final ListingService listings;
    public ListingController(ListingService listings) { this.listings = listings; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RentalListing create(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @Valid @RequestBody Create request) {
        return listings.create(actor, organizationId, propertyId, request);
    }
    @GetMapping
    public List<RentalListing> list(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return listings.list(actor, organizationId, propertyId, limit, offset);
    }
    @GetMapping("/{id}")
    public RentalListing get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id) { return listings.get(actor, organizationId, propertyId, id); }
    @PutMapping("/{id}")
    public RentalListing update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody Update request) {
        return listings.update(actor, organizationId, propertyId, id, request);
    }
    @PostMapping("/{id}/publish")
    public RentalListing publish(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody Publish request) {
        return listings.publish(actor, organizationId, propertyId, id, request);
    }
    @PostMapping("/{id}/withdraw")
    public RentalListing withdraw(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody Withdraw request) {
        return listings.withdraw(actor, organizationId, propertyId, id, request);
    }
}
