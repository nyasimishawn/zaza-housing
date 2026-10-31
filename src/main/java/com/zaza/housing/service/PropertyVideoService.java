package com.zaza.housing.service;

import com.zaza.housing.dto.ChangeVideoStatusRequest;
import com.zaza.housing.dto.PropertyVideoResponse;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.dto.PublicVideoResponse;
import com.zaza.housing.dto.UpdateVideoRequest;
import com.zaza.housing.dto.VideoReferenceRequest;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.enums.VideoStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ProcessedVideo;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyVideo;
import com.zaza.housing.model.VideoContent;
import com.zaza.housing.repository.LocalVideoStorageRepository;
import com.zaza.housing.repository.PropertyVideoRepository;
import com.zaza.housing.util.Pagination;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PropertyVideoService {
    private static final Logger log = LoggerFactory.getLogger(PropertyVideoService.class);
    private final PropertyService properties;
    private final PropertyVideoRepository videos;
    private final LocalVideoStorageRepository storage;
    private final AuditService audit;
    private final VideoProcessingService processing;
    private final TransactionTemplate transactions;

    public PropertyVideoService(PropertyService properties, PropertyVideoRepository videos,
            LocalVideoStorageRepository storage, AuditService audit, VideoProcessingService processing, PlatformTransactionManager manager) {
        this.properties = properties; this.videos = videos; this.storage = storage; this.audit = audit;
        this.processing = processing; this.transactions = new TransactionTemplate(manager);
    }

    public PropertyVideoResponse upload(AuthenticatedUser actor, UUID organization, UUID propertyId, MultipartFile file, String caption) {
        if (caption == null || caption.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "Video caption must contain at most 200 characters.");
        // Encoding runs without a database transaction or property locks.
        transactions.executeWithoutResult(status -> {
            requireOpen(properties.lockForManagement(actor, organization, propertyId));
            checkCapacity(propertyId);
        });
        final Path workspace;
        try { workspace = storage.createWorkspace(); }
        catch (IOException error) { throw storageUnavailable(); }
        try {
            ProcessedVideo processed = processing.process(file, workspace);
            return storeProcessed(actor, organization, propertyId, processed, caption);
        } catch (IOException error) { throw storageUnavailable(); }
        finally {
            try { storage.removeWorkspace(workspace); }
            catch (IOException error) { log.warn("Could not clean a video processing workspace: {}", workspace.getFileName()); }
        }
    }

    public PropertyVideoResponse storeProcessed(AuthenticatedUser actor, UUID organization, UUID propertyId,
            ProcessedVideo processed, String caption) {
        return transactions.execute(status -> {
                // Memberships, delegation, archival, and capacity may have changed during processing.
                requireOpen(properties.lockForManagement(actor, organization, propertyId));
                checkCapacity(propertyId);
                final String key;
                try { key = storage.save(processed.path()); }
                catch (IOException error) { throw storageUnavailable(); }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        if (completion != STATUS_COMMITTED) {
                            try { storage.removeUncommitted(key); }
                            catch (IOException error) { log.warn("Could not remove an uncommitted video: {}", key); }
                        }
                    }
                });
                if (processed.posterPath() != null) try { storage.savePoster(key, processed.posterPath()); }
                catch (IOException error) { throw storageUnavailable(); }
                UUID id = videos.create(propertyId, actor.id(), key, processed, caption.trim());
                var result = response(organization, find(propertyId, id));
                audit.recordSnapshot(actor.id(), organization, "PROPERTY_VIDEO_UPLOADED", id, null, result);
                return result;
        });
    }

    private static ApiException storageUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Video storage is unavailable. Try again later.");
    }

    public List<PropertyVideoResponse> list(AuthenticatedUser actor, UUID organization, UUID propertyId,
            boolean includeArchived, int limit, int offset) {
        properties.get(actor, organization, propertyId);
        Pagination.validate(limit, offset);
        return videos.list(propertyId, includeArchived, limit, offset).stream().map(video -> response(organization, video)).toList();
    }

    public VideoContent privateContent(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id) {
        properties.get(actor, organization, propertyId);
        return content(find(propertyId, id));
    }
    public PropertyVideoResponse get(AuthenticatedUser actor,UUID organization,UUID propertyId,UUID id) {
        properties.get(actor,organization,propertyId);return response(organization,find(propertyId,id));
    }

    public VideoContent publicContent(PublicListingResponse approvedListing, UUID id) {
        if (approvedListing.videos().stream().noneMatch(video -> video.id().equals(id))) throw notFound();
        PropertyVideo video = videos.publishedReference(approvedListing.id(), id).orElseThrow(PropertyVideoService::notFound);
        return content(video);
    }

    public com.zaza.housing.model.ImageContent privatePoster(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id) {
        properties.get(actor, organization, propertyId);
        return poster(find(propertyId,id));
    }
    public com.zaza.housing.model.ImageContent publicPoster(PublicListingResponse approvedListing, UUID id) {
        if(approvedListing.videos().stream().noneMatch(v -> v.id().equals(id))) throw notFound();
        return poster(videos.publishedReference(approvedListing.id(),id).orElseThrow(PropertyVideoService::notFound));
    }
    private com.zaza.housing.model.ImageContent poster(PropertyVideo video) {
        requireActive(video);
        if(video.posterSize()==null) throw notFound();
        try { return new com.zaza.housing.model.ImageContent(video.id(),storage.poster(video.storageKey()),"image/jpeg",video.posterSize()); }
        catch(IOException error) { throw notFound(); }
    }

    @Transactional
    public PropertyVideoResponse update(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, UpdateVideoRequest request) {
        requireOpen(properties.lockForManagement(actor, organization, propertyId));
        PropertyVideo previous = find(propertyId, id);
        requireActive(previous);
        checkVersion(previous, request.version());
        videos.update(propertyId, id, request.caption().trim());
        return record(actor, organization, previous, "PROPERTY_VIDEO_UPDATED");
    }

    @Transactional
    public PropertyVideoResponse changeStatus(AuthenticatedUser actor, UUID organization, UUID propertyId, UUID id, ChangeVideoStatusRequest request) {
        Property property = properties.lockForManagement(actor, organization, propertyId);
        PropertyVideo previous = find(propertyId, id);
        checkVersion(previous, request.version());
        if (request.status() == VideoStatus.ACTIVE) {
            requireOpen(property);
            if (previous.status() == VideoStatus.ARCHIVED) checkCapacity(propertyId);
        }
        videos.changeStatus(propertyId, id, request.status());
        return record(actor, organization, previous, "PROPERTY_VIDEO_STATUS_CHANGED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PublicVideoResponse> forPublication(AuthenticatedUser actor, UUID organization, UUID propertyId,
            UUID listingId, List<VideoReferenceRequest> selections) {
        properties.lockForManagement(actor, organization, propertyId);
        var seen = new HashSet<UUID>();
        var result = new ArrayList<PublicVideoResponse>();
        for (var selection : selections) {
            if (!seen.add(selection.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "Select each video only once.");
            PropertyVideo video = find(propertyId, selection.id());
            requireActive(video);
            checkVersion(video, selection.version());
            result.add(new PublicVideoResponse(video.id(), video.caption(), video.contentType(), video.width(), video.height(), video.durationSeconds(),
                    "/api/v1/listings/" + listingId + "/videos/" + video.id(),
                    video.posterSize()==null ? null : "/api/v1/listings/" + listingId + "/videos/" + video.id()+"/poster"));
        }
        return List.copyOf(result);
    }

    private PropertyVideo find(UUID propertyId, UUID id) { return videos.find(propertyId, id).orElseThrow(PropertyVideoService::notFound); }
    private VideoContent content(PropertyVideo video) {
        requireActive(video);
        try { return new VideoContent(video.id(), storage.read(video.storageKey()), video.contentType(), video.byteSize()); }
        catch (IOException error) { throw notFound(); }
    }
    private PropertyVideoResponse record(AuthenticatedUser actor, UUID organization, PropertyVideo previous, String action) {
        var result = response(organization, find(previous.propertyId(), previous.id()));
        audit.recordSnapshot(actor.id(), organization, action, previous.id(), response(organization, previous), result);
        return result;
    }
    private static PropertyVideoResponse response(UUID organization, PropertyVideo video) {
        return new PropertyVideoResponse(video.id(), video.caption(), video.contentType(), video.width(), video.height(), video.durationSeconds(),
                video.byteSize(), video.status(), video.version(), "/api/v1/organizations/" + organization + "/properties/" + video.propertyId()
                + "/videos/" + video.id() + "/content", video.createdAt(), video.updatedAt(), video.contentHash(),
                video.posterSize()==null ? null : "/api/v1/organizations/"+organization+"/properties/"+video.propertyId()+"/videos/"+video.id()+"/poster",
                video.posterHash(),video.posterSize());
    }
    private void checkCapacity(UUID propertyId) {
        if (videos.activeCount(propertyId) >= 5) throw new ApiException(HttpStatus.CONFLICT, "A property can have at most 5 active videos.");
    }
    private static void requireOpen(Property property) {
        if (property.status() == PropertyStatus.ARCHIVED) throw new ApiException(HttpStatus.CONFLICT, "Restore the property before editing videos.");
    }
    private static void checkVersion(PropertyVideo video, long expected) {
        if (video.version() != expected) throw new ApiException(HttpStatus.CONFLICT, "The video has changed. Reload it before saving or publishing.");
    }
    private static void requireActive(PropertyVideo video) { if (video.status() != VideoStatus.ACTIVE) throw notFound(); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "Video not found."); }
}
