package com.zaza.housing.controller;

import com.zaza.housing.dto.CreateListingRequest;
import com.zaza.housing.dto.PublishListingRequest;
import com.zaza.housing.dto.UpdateListingRequest;
import com.zaza.housing.dto.WithdrawListingRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.RentalListing;
import com.zaza.housing.service.ListingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/listings")
public class ListingController {
    private final ListingService listings;
    public ListingController(ListingService listings) { this.listings = listings; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RentalListing create(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @Valid @RequestBody CreateListingRequest request) {
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
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody UpdateListingRequest request) {
        return listings.update(actor, organizationId, propertyId, id, request);
    }
    @PostMapping("/{id}/publish")
    public RentalListing publish(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody PublishListingRequest request) {
        return listings.publish(actor, organizationId, propertyId, id, request);
    }
    @PostMapping("/{id}/withdraw")
    public RentalListing withdraw(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody WithdrawListingRequest request) {
        return listings.withdraw(actor, organizationId, propertyId, id, request);
    }
}
