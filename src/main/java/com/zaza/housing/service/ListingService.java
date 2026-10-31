package com.zaza.housing.service;

import com.zaza.housing.dto.CreateListingRequest;
import com.zaza.housing.dto.EnquiryListingResponse;
import com.zaza.housing.dto.ListingSearchRequest;
import com.zaza.housing.dto.ListingSearchResponse;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.dto.PublishListingRequest;
import com.zaza.housing.dto.UpdateListingRequest;
import com.zaza.housing.dto.WithdrawListingRequest;
import com.zaza.housing.enums.ListingSort;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.enums.UnitAvailability;
import com.zaza.housing.enums.UnitStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyUnit;
import com.zaza.housing.model.RentalListing;
import com.zaza.housing.repository.ListingRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListingService {
    private final ListingRepository listings;
    private final PropertyService properties;
    private final AuditService audit;
    private final Clock clock;
    private final PropertyImageService images;
    private final PropertyVideoService videos;

    public ListingService(ListingRepository listings, PropertyService properties, AuditService audit, Clock clock, PropertyImageService images, PropertyVideoService videos) {
        this.listings = listings; this.properties = properties; this.audit = audit; this.clock = clock;
        this.images = images; this.videos = videos;
    }

    @Transactional
    public RentalListing create(AuthenticatedUser actor, UUID organization, UUID propertyId, CreateListingRequest request) {
        Property property = properties.lockForManagement(actor, organization, propertyId);
        requireOpen(property);
        PropertyUnit unit = properties.unit(actor, organization, propertyId, request.unitId());
        if (unit.status() == UnitStatus.ARCHIVED) throw conflict("Restore the unit before creating a listing.");
        UUID id = listings.create(propertyId, actor.id(), request);
        RentalListing result = find(propertyId, id);
        audit.recordSnapshot(actor.id(), organization, "LISTING_CREATED", id, null, result);
        return result;
    }

    public RentalListing get(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id) {
        properties.get(actor, organization, propertyId);
        return find(propertyId, id);
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public com.zaza.housing.dto.PublicationMediaResponse mediaSelection(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id) {
        properties.get(actor,organization,propertyId);
        var listing=find(propertyId,id);
        return new com.zaza.housing.dto.PublicationMediaResponse(listing.version(),listings.selectedImages(id),listings.selectedVideos(id));
    }

    public List<RentalListing> list(AuthenticatedUser actor, UUID organization, UUID propertyId, int limit, int offset) {
        properties.get(actor, organization, propertyId);
        Pagination.validate(limit, offset);
        return listings.list(propertyId, limit, offset);
    }

    @Transactional
    public RentalListing update(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, UpdateListingRequest request) {
        requireOpen(properties.lockForManagement(actor, organization, propertyId));
        RentalListing previous = find(propertyId, id);
        checkVersion(previous.version(), request.version());
        listings.update(propertyId, id, request);
        return record(actor, organization, previous, "LISTING_UPDATED");
    }

    @Transactional
    public RentalListing publish(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, PublishListingRequest request) {
        Property property = properties.lockForManagement(actor, organization, propertyId);
        RentalListing previous = find(propertyId, id);
        PropertyUnit unit = properties.unit(actor, organization, propertyId, previous.unitId());
        checkVersion(previous.version(), request.version());
        checkVersion(property.version(), request.propertyVersion());
        checkVersion(unit.version(), request.unitVersion());
        if (property.status() != PropertyStatus.ACTIVE || unit.status() != UnitStatus.ACTIVE || unit.availability() != UnitAvailability.AVAILABLE) {
            throw conflict("Publishing requires an active property and an active, available unit.");
        }
        boolean disclose = previous.showExactLocation();
        PublicListingResponse snapshot = new PublicListingResponse(id, previous.title(), previous.description(), property.type(),
                property.countryCode(), property.city(), disclose ? property.address() : null,
                disclose ? property.latitude() : null, disclose ? property.longitude() : null,
                unit.bedrooms(), unit.bathrooms(), unit.floorAreaSqm(), unit.monthlyRent(), unit.deposit(), unit.currency(), clock.instant(),
                images.forPublication(actor, organization, propertyId, id, request.images()),
                videos.forPublication(actor, organization, propertyId, id, request.videos()));
        listings.publish(propertyId, id, organization, request, snapshot);
        listings.replaceImageReferences(propertyId, id, request.images());
        listings.replaceVideoReferences(propertyId, id, request.videos());
        return record(actor, organization, previous, "LISTING_PUBLISHED");
    }

    @Transactional
    public RentalListing withdraw(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, WithdrawListingRequest request) {
        // Withdrawing remains allowed for archived inventory, so owners can explicitly end publication.
        properties.lockForManagement(actor, organization, propertyId);
        RentalListing previous = find(propertyId, id);
        checkVersion(previous.version(), request.version());
        listings.withdraw(propertyId, id, request.version());
        return record(actor, organization, previous, "LISTING_WITHDRAWN");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RentalListing applicationSource(UUID id) {
        RentalListing candidate = listings.applicationSource(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found."));
        properties.lockForApplication(candidate.propertyId());
        // All listing and inventory writes use this property lock. Recheck visibility after waiting.
        RentalListing current = find(candidate.propertyId(), id);
        if (!current.publiclyVisible()) throw new ApiException(HttpStatus.NOT_FOUND, "Listing not found.");
        return current;
    }

    public EnquiryListingResponse contactListing(UUID id) {
        RentalListing source = listings.applicationSource(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found."));
        return new EnquiryListingResponse(source.version(), source.publicSnapshot());
    }

    public PublicListingResponse publicListing(UUID id) {
        return listings.publicListing(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found."));
    }

    public ListingSearchResponse search(ListingSearchRequest query) {
        int limit = query.limit() == null ? 50 : query.limit();
        int offset = query.offset() == null ? 0 : query.offset();
        if (query.q() != null && query.q().trim().length() < 2) throw badRequest("Search text must contain at least two characters.");
        if (query.city() != null && query.city().isBlank()) throw badRequest("City cannot be blank.");
        if (query.countryCode() != null && !Arrays.asList(Locale.getISOCountries()).contains(query.countryCode().toUpperCase(Locale.ROOT))) {
            throw badRequest("Use a valid ISO country code.");
        }
        if (query.currency() != null) {
            try { Currency.getInstance(query.currency().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw badRequest("Use a valid ISO currency code."); }
        }
        boolean prices = query.minRent() != null || query.maxRent() != null || query.sort() == ListingSort.PRICE_ASC || query.sort() == ListingSort.PRICE_DESC;
        if (prices && query.currency() == null) throw badRequest("Specify currency when filtering or sorting by price.");
        if (query.minRent() != null && query.maxRent() != null && query.minRent().compareTo(query.maxRent()) > 0) {
            throw badRequest("Minimum rent cannot exceed maximum rent.");
        }
        if (query.minBedrooms() != null && query.maxBedrooms() != null && query.minBedrooms() > query.maxBedrooms()) {
            throw badRequest("Minimum bedrooms cannot exceed maximum bedrooms.");
        }
        List<PublicListingResponse> rows = listings.search(query, limit + 1, offset);
        return new ListingSearchResponse(List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), limit, offset, rows.size() > limit);
    }

    private RentalListing find(UUID propertyId, UUID id) {
        return listings.find(propertyId, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found."));
    }

    private RentalListing record(AuthenticatedUser actor, UUID organization, RentalListing previous, String action) {
        RentalListing result = find(previous.propertyId(), previous.id());
        audit.recordSnapshot(actor.id(), organization, action, previous.id(), previous, result);
        return result;
    }

    private static void checkVersion(long actual, long expected) {
        if (actual != expected) throw conflict("The listing or source inventory has changed. Reload and review it before publishing or saving.");
    }
    private static void requireOpen(Property property) {
        if (property.status() == PropertyStatus.ARCHIVED) throw conflict("Restore the property before editing listings.");
    }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
