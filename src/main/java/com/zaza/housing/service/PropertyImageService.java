package com.zaza.housing.service;

import com.zaza.housing.dto.ChangeImageStatusRequest;
import com.zaza.housing.dto.ImageReferenceRequest;
import com.zaza.housing.dto.PropertyImageResponse;
import com.zaza.housing.dto.PublicImageResponse;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.dto.UpdateImageRequest;
import com.zaza.housing.enums.ImageStatus;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyImage;
import com.zaza.housing.repository.LocalImageStorageRepository;
import com.zaza.housing.repository.PropertyImageRepository;
import com.zaza.housing.util.ImageProcessor;
import com.zaza.housing.util.Pagination;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PropertyImageService {
    private static final Logger log = LoggerFactory.getLogger(PropertyImageService.class);
    private final PropertyService properties;
    private final PropertyImageRepository images;
    private final LocalImageStorageRepository storage;
    private final AuditService audit;

    public PropertyImageService(PropertyService properties, PropertyImageRepository images,
            LocalImageStorageRepository storage, AuditService audit) {
        this.properties = properties; this.images = images; this.storage = storage; this.audit = audit;
    }

    @Transactional
    public PropertyImageResponse upload(AuthenticatedUser actor, UUID organization, UUID propertyId, MultipartFile file, String altText) {
        requireOpen(properties.lockForManagement(actor, organization, propertyId));
        checkCapacity(propertyId);
        if (altText == null || altText.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "Image description must contain at most 200 characters.");
        var processed = ImageProcessor.process(file);
        final String key;
        try { key = storage.save(processed); }
        catch (IOException error) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is unavailable. Try again later."); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { storage.removeUncommitted(key); }
                    catch (IOException error) { log.warn("Could not remove an uncommitted image: {}", key); }
                }
            }
        });
        UUID id = images.create(propertyId, actor.id(), key, processed, altText.trim());
        var result = response(organization, find(propertyId, id));
        audit.recordSnapshot(actor.id(), organization, "PROPERTY_IMAGE_UPLOADED", id, null, result);
        return result;
    }

    public List<PropertyImageResponse> list(AuthenticatedUser actor, UUID organization, UUID propertyId,
            boolean includeArchived, int limit, int offset) {
        properties.get(actor, organization, propertyId);
        Pagination.validate(limit, offset);
        return images.list(propertyId, includeArchived, limit, offset).stream().map(image -> response(organization, image)).toList();
    }

    public ImageContent privateContent(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id) {
        properties.get(actor, organization, propertyId);
        return content(find(propertyId, id));
    }

    public ImageContent publicContent(PublicListingResponse approvedListing, UUID id) {
        if (approvedListing.images().stream().noneMatch(image -> image.id().equals(id))) throw notFound();
        PropertyImage image = images.publishedReference(approvedListing.id(), id).orElseThrow(PropertyImageService::notFound);
        return content(image);
    }

    @Transactional
    public PropertyImageResponse update(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, UpdateImageRequest request) {
        requireOpen(properties.lockForManagement(actor, organization, propertyId));
        PropertyImage previous = find(propertyId, id);
        requireActive(previous);
        checkVersion(previous, request.version());
        images.update(propertyId, id, request.altText().trim());
        return record(actor, organization, previous, "PROPERTY_IMAGE_UPDATED");
    }

    @Transactional
    public PropertyImageResponse changeStatus(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, ChangeImageStatusRequest request) {
        Property property = properties.lockForManagement(actor, organization, propertyId);
        PropertyImage previous = find(propertyId, id);
        checkVersion(previous, request.version());
        if (request.status() == ImageStatus.ACTIVE) {
            requireOpen(property);
            if (previous.status() == ImageStatus.ARCHIVED) checkCapacity(propertyId);
        }
        images.changeStatus(propertyId, id, request.status());
        return record(actor, organization, previous, "PROPERTY_IMAGE_STATUS_CHANGED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PublicImageResponse> forPublication(AuthenticatedUser actor, UUID organization, UUID propertyId,
            UUID listingId, List<ImageReferenceRequest> selections) {
        properties.lockForManagement(actor, organization, propertyId);
        var seen = new HashSet<UUID>();
        var result = new ArrayList<PublicImageResponse>();
        for (var selection : selections) {
            if (!seen.add(selection.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "Select each image only once.");
            PropertyImage image = find(propertyId, selection.id());
            requireActive(image);
            checkVersion(image, selection.version());
            result.add(new PublicImageResponse(image.id(), image.altText(), image.contentType(), image.width(), image.height(),
                    "/api/v1/listings/" + listingId + "/images/" + image.id()));
        }
        return List.copyOf(result);
    }

    private PropertyImage find(UUID propertyId, UUID id) { return images.find(propertyId, id).orElseThrow(PropertyImageService::notFound); }
    private ImageContent content(PropertyImage image) {
        requireActive(image);
        try { return new ImageContent(image.id(), storage.read(image.storageKey()), image.contentType(), image.byteSize()); }
        catch (IOException error) { throw notFound(); }
    }
    private PropertyImageResponse record(AuthenticatedUser actor, UUID organization, PropertyImage previous, String action) {
        var result = response(organization, find(previous.propertyId(), previous.id()));
        audit.recordSnapshot(actor.id(), organization, action, previous.id(), response(organization, previous), result);
        return result;
    }
    private static PropertyImageResponse response(UUID organization, PropertyImage image) {
        return new PropertyImageResponse(image.id(), image.altText(), image.contentType(), image.width(), image.height(),
                image.byteSize(), image.status(), image.version(), "/api/v1/organizations/" + organization + "/properties/" + image.propertyId()
                + "/images/" + image.id() + "/content", image.createdAt(), image.updatedAt());
    }
    private void checkCapacity(UUID propertyId) {
        if (images.activeCount(propertyId) >= 30) throw new ApiException(HttpStatus.CONFLICT, "A property can have at most 30 active images.");
    }
    private static void requireOpen(Property property) {
        if (property.status() == PropertyStatus.ARCHIVED) throw new ApiException(HttpStatus.CONFLICT, "Restore the property before editing images.");
    }
    private static void checkVersion(PropertyImage image, long expected) {
        if (image.version() != expected) throw new ApiException(HttpStatus.CONFLICT, "The image has changed. Reload it before saving or publishing.");
    }
    private static void requireActive(PropertyImage image) { if (image.status() != ImageStatus.ACTIVE) throw notFound(); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "Image not found."); }
}
