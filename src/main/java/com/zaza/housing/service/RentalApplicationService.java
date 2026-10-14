package com.zaza.housing.service;

import com.zaza.housing.dto.ApplicantApplicationResponse;
import com.zaza.housing.dto.ApplicantProfileSnapshot;
import com.zaza.housing.dto.ApplicationDashboardResponse;
import com.zaza.housing.dto.ApplicationDecisionRequest;
import com.zaza.housing.dto.ApplicationDetails;
import com.zaza.housing.dto.ApplicationEventResponse;
import com.zaza.housing.dto.ApplicationListingResponse;
import com.zaza.housing.dto.ApplicationNoteResponse;
import com.zaza.housing.dto.ApplicationVersionRequest;
import com.zaza.housing.dto.CreateApplicationNoteRequest;
import com.zaza.housing.dto.CreateApplicationRequest;
import com.zaza.housing.dto.RefreshApplicationListingRequest;
import com.zaza.housing.dto.ReviewApplicationResponse;
import com.zaza.housing.dto.SubmitApplicationRequest;
import com.zaza.housing.dto.UpdateApplicationRequest;
import com.zaza.housing.enums.ApplicationActorType;
import com.zaza.housing.enums.ApplicationEventType;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.RentalApplication;
import com.zaza.housing.model.RentalListing;
import com.zaza.housing.model.TenantProfile;
import com.zaza.housing.repository.AuthRepository;
import com.zaza.housing.repository.RentalApplicationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RentalApplicationService {
    private final RentalApplicationRepository applications;
    private final TenantProfileService profiles;
    private final PropertyService properties;
    private final ListingService listings;
    private final UnitReservationService reservations;
    private final NotificationService notifications;
    private final AuthRepository users;
    private final AuditService audit;
    private final Clock clock;
    public RentalApplicationService(RentalApplicationRepository applications, TenantProfileService profiles,
            PropertyService properties, ListingService listings, UnitReservationService reservations,
            NotificationService notifications, AuthRepository users, AuditService audit, Clock clock) {
        this.applications = applications; this.profiles = profiles; this.properties = properties; this.listings = listings;
        this.reservations = reservations; this.notifications = notifications; this.users = users; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public ApplicationListingResponse preview(UUID listingId) {
        RentalListing source = listings.applicationSource(listingId);
        return new ApplicationListingResponse(source.version(), source.publicSnapshot());
    }
    @Transactional
    public ApplicantApplicationResponse create(AuthenticatedUser actor, CreateApplicationRequest request) {
        RentalListing source = listings.applicationSource(request.listingId());
        version(source.version(), request.listingVersion());
        TenantProfile profile = profiles.forApplication(actor.id());
        if (applications.hasActive(actor.id(), source.unitId())) throw conflict("You already have an active application for this unit.");
        UUID id = applications.create(actor.id(), snapshot(actor, profile), profile, source, normalize(request.details()));
        RentalApplication result = own(actor, id, false);
        record(actor, null, null, result, ApplicationEventType.CREATED, "Application draft created.", false);
        return applicant(result);
    }
    public ApplicantApplicationResponse getOwn(AuthenticatedUser actor, UUID id) { return applicant(own(actor, id, false)); }
    public List<ApplicantApplicationResponse> listOwn(AuthenticatedUser actor, ApplicationStatus status, int limit, int offset) {
        Pagination.validate(limit, offset);
        return applications.listOwn(actor.id(), status, limit, offset).stream().map(RentalApplicationService::applicant).toList();
    }
    public ApplicationDashboardResponse ownDashboard(AuthenticatedUser actor) { return dashboard(applications.counts(actor.id(), false), false); }

    @Transactional
    public ApplicantApplicationResponse update(AuthenticatedUser actor, UUID id, UpdateApplicationRequest request) {
        RentalApplication previous = lockOwn(actor, id);
        version(previous.version(), request.version()); editable(previous);
        applications.updateDetails(id, previous.version(), normalize(request.details()));
        RentalApplication result = own(actor, id, false);
        record(actor, null, previous, result, ApplicationEventType.DETAILS_UPDATED, "Application details updated.", false);
        return applicant(result);
    }
    @Transactional
    public ApplicantApplicationResponse refreshListing(AuthenticatedUser actor, UUID id, RefreshApplicationListingRequest request) {
        RentalApplication previous = lockOwn(actor, id);
        version(previous.version(), request.version()); editable(previous);
        RentalListing source = currentSource(previous);
        version(source.version(), request.listingVersion());
        applications.refreshListing(id, previous.version(), source);
        RentalApplication result = own(actor, id, false);
        record(actor, null, previous, result, ApplicationEventType.LISTING_REFRESHED, "Current listing terms reviewed and selected.", false);
        return applicant(result);
    }
    @Transactional
    public ApplicantApplicationResponse submit(AuthenticatedUser actor, UUID id, SubmitApplicationRequest request) {
        RentalApplication previous = lockOwn(actor, id);
        version(previous.version(), request.version()); editable(previous);
        if (!Boolean.TRUE.equals(request.consentToShare())) throw invalid("Consent is required to share the application with authorized reviewers.");
        matchingSource(previous, currentSource(previous));
        version(previous.listingVersion(), request.listingVersion());
        normalize(previous.details());
        TenantProfile profile = profiles.forApplication(actor.id());
        version(profile.version(), request.profileVersion());
        applications.submit(id, previous.version(), profile, snapshot(actor, profile));
        RentalApplication result = own(actor, id, false);
        record(actor, null, previous, result, ApplicationEventType.SUBMITTED, "Application submitted for review.", false);
        return applicant(result);
    }
    @Transactional
    public ApplicantApplicationResponse withdraw(AuthenticatedUser actor, UUID id, ApplicationVersionRequest request) {
        RentalApplication previous = lockOwn(actor, id);
        version(previous.version(), request.version());
        requireState(previous, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.INFORMATION_REQUESTED, ApplicationStatus.APPROVED);
        if (previous.status() == ApplicationStatus.APPROVED) reservations.release(actor.id(), null, previous);
        applications.transition(id, previous.version(), ApplicationStatus.WITHDRAWN);
        RentalApplication result = own(actor, id, false);
        record(actor, null, previous, result, ApplicationEventType.WITHDRAWN, "The applicant withdrew this application.", false);
        return applicant(result);
    }
    public List<ApplicationEventResponse> ownHistory(AuthenticatedUser actor, UUID id, int limit, int offset) {
        own(actor, id, false); Pagination.validate(limit, offset);
        return applications.events(id, limit, offset);
    }

    public ReviewApplicationResponse getReview(AuthenticatedUser actor, UUID organization, UUID property, UUID id) {
        properties.getForManagement(actor, organization, property);
        return reviewer(review(property, id, false));
    }
    public List<ReviewApplicationResponse> listReview(AuthenticatedUser actor, UUID organization, UUID property,
            ApplicationStatus status, int limit, int offset) {
        properties.getForManagement(actor, organization, property); Pagination.validate(limit, offset);
        return applications.listReview(property, status, limit, offset).stream().map(RentalApplicationService::reviewer).toList();
    }
    public ApplicationDashboardResponse reviewDashboard(AuthenticatedUser actor, UUID organization, UUID property) {
        properties.getForManagement(actor, organization, property);
        return dashboard(applications.counts(property, true), true);
    }
    @Transactional
    public ReviewApplicationResponse startReview(AuthenticatedUser actor, UUID organization, UUID property, UUID id, ApplicationVersionRequest request) {
        Property source = properties.lockForManagement(actor, organization, property);
        requireOpen(source);
        RentalApplication previous = decision(actor, property, id, request.version(), ApplicationStatus.SUBMITTED);
        return transition(actor, organization, previous, ApplicationStatus.UNDER_REVIEW, ApplicationEventType.REVIEW_STARTED, "Your application is under review.");
    }
    @Transactional
    public ReviewApplicationResponse requestInformation(AuthenticatedUser actor, UUID organization, UUID property, UUID id, ApplicationDecisionRequest request) {
        requireOpen(properties.lockForManagement(actor, organization, property));
        RentalApplication previous = decision(actor, property, id, request.version(), ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW);
        return transition(actor, organization, previous, ApplicationStatus.INFORMATION_REQUESTED, ApplicationEventType.INFORMATION_REQUESTED, request.message().trim());
    }
    @Transactional
    public ReviewApplicationResponse reject(AuthenticatedUser actor, UUID organization, UUID property, UUID id, ApplicationDecisionRequest request) {
        properties.lockForManagement(actor, organization, property);
        RentalApplication previous = decision(actor, property, id, request.version(), ApplicationStatus.SUBMITTED,
                ApplicationStatus.UNDER_REVIEW, ApplicationStatus.INFORMATION_REQUESTED);
        return transition(actor, organization, previous, ApplicationStatus.REJECTED, ApplicationEventType.REJECTED, request.message().trim());
    }
    @Transactional
    public ReviewApplicationResponse approve(AuthenticatedUser actor, UUID organization, UUID property, UUID id, ApplicationDecisionRequest request) {
        properties.lockForManagement(actor, organization, property);
        RentalApplication previous = decision(actor, property, id, request.version(), ApplicationStatus.UNDER_REVIEW);
        matchingSource(previous, currentSource(previous));
        if (users.findById(previous.applicantId()).isEmpty()) throw conflict("This applicant account is no longer active.");
        reservations.reserve(actor.id(), organization, previous);
        return transition(actor, organization, previous, ApplicationStatus.APPROVED, ApplicationEventType.APPROVED, request.message().trim());
    }
    @Transactional
    public ReviewApplicationResponse cancelApproval(AuthenticatedUser actor, UUID organization, UUID property, UUID id, ApplicationDecisionRequest request) {
        properties.lockForManagement(actor, organization, property);
        RentalApplication previous = decision(actor, property, id, request.version(), ApplicationStatus.APPROVED);
        reservations.release(actor.id(), organization, previous);
        return transition(actor, organization, previous, ApplicationStatus.CANCELLED, ApplicationEventType.APPROVAL_CANCELLED, request.message().trim());
    }
    public List<ApplicationEventResponse> reviewHistory(AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
        getReview(actor, organization, property, id); Pagination.validate(limit, offset);
        return applications.events(id, limit, offset);
    }
    @Transactional
    public ApplicationNoteResponse addNote(AuthenticatedUser actor, UUID organization, UUID property, UUID id, CreateApplicationNoteRequest request) {
        properties.lockForManagement(actor, organization, property);
        RentalApplication current = review(property, id, true);
        version(current.version(), request.version());
        var result = applications.addNote(id, actor.id(), request.text().trim());
        audit.recordSnapshot(actor.id(), organization, "APPLICATION_REVIEWER_NOTE_ADDED", id, null, result);
        return result;
    }
    public List<ApplicationNoteResponse> notes(AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
        getReview(actor, organization, property, id); Pagination.validate(limit, offset);
        return applications.notes(id, limit, offset);
    }

    private RentalApplication lockOwn(AuthenticatedUser actor, UUID id) {
        RentalApplication candidate = own(actor, id, false);
        properties.lockForApplication(candidate.propertyId());
        return own(actor, id, true);
    }
    private RentalApplication decision(AuthenticatedUser actor, UUID property, UUID id, long expected, ApplicationStatus... allowed) {
        RentalApplication current = review(property, id, true);
        if (current.applicantId().equals(actor.id())) throw new ApiException(HttpStatus.FORBIDDEN, "You cannot review your own application.");
        version(current.version(), expected); requireState(current, allowed);
        return current;
    }
    private ReviewApplicationResponse transition(AuthenticatedUser actor, UUID organization, RentalApplication previous,
            ApplicationStatus status, ApplicationEventType type, String message) {
        applications.transition(previous.id(), previous.version(), status);
        RentalApplication result = review(previous.propertyId(), previous.id(), false);
        record(actor, organization, previous, result, type, message, true);
        return reviewer(result);
    }
    private void record(AuthenticatedUser actor, UUID organization, RentalApplication previous, RentalApplication current,
            ApplicationEventType type, String message, boolean notify) {
        UUID event = applications.event(current, previous == null ? null : previous.status(), actor.id(),
                notify ? ApplicationActorType.REVIEWER : ApplicationActorType.APPLICANT, type, message);
        audit.recordSnapshot(actor.id(), organization, "APPLICATION_" + type.name(), current.id(),
                previous == null ? null : applicant(previous), applicant(current));
        if (notify) notifications.applicationChanged(current.applicantId(), current.id(), event, type, message);
    }
    private RentalListing currentSource(RentalApplication application) {
        try { return listings.applicationSource(application.listingId()); }
        catch (ApiException error) {
            if (error.status() != HttpStatus.NOT_FOUND) throw error;
            throw conflict("The listing is no longer available. A reviewer must resolve this application before it can proceed.");
        }
    }
    private static void matchingSource(RentalApplication application, RentalListing listing) {
        if (application.listingVersion() != listing.version() || application.propertyVersion() != listing.sourcePropertyVersion()
                || application.unitVersion() != listing.sourceUnitVersion()) {
            throw conflict("Listing terms have changed. Request information, review the current listing, refresh the application, and resubmit.");
        }
    }
    private RentalApplication own(AuthenticatedUser actor, UUID id, boolean lock) {
        return applications.own(actor.id(), id, lock).orElseThrow(RentalApplicationService::notFound);
    }
    private RentalApplication review(UUID property, UUID id, boolean lock) { return applications.review(property, id, lock).orElseThrow(RentalApplicationService::notFound); }
    private static ApplicantProfileSnapshot snapshot(AuthenticatedUser actor, TenantProfile profile) { return new ApplicantProfileSnapshot(actor.email(), profile.details()); }
    private static ApplicantApplicationResponse applicant(RentalApplication application) {
        return new ApplicantApplicationResponse(application.id(), application.listingId(), application.status(), application.version(),
                application.listingVersion(), application.profileVersion(), application.details(), application.applicant(), application.listing(),
                application.submittedAt(), application.createdAt(), application.updatedAt());
    }
    private static ReviewApplicationResponse reviewer(RentalApplication application) {
        return new ReviewApplicationResponse(application.id(), application.applicantId(), application.listingId(), application.status(), application.version(),
                application.listingVersion(), application.profileVersion(), application.details(), application.applicant(), application.listing(),
                application.submittedAt(), application.createdAt(), application.updatedAt());
    }
    private static ApplicationDashboardResponse dashboard(Map<ApplicationStatus, Long> counts, boolean reviewer) {
        long action = counts.get(reviewer ? ApplicationStatus.SUBMITTED : ApplicationStatus.DRAFT)
                + counts.get(reviewer ? ApplicationStatus.UNDER_REVIEW : ApplicationStatus.INFORMATION_REQUESTED);
        return new ApplicationDashboardResponse(counts, counts.values().stream().mapToLong(Long::longValue).sum(), action);
    }
    private ApplicationDetails normalize(ApplicationDetails details) {
        LocalDate today = LocalDate.now(clock);
        if (details.requestedMoveInDate().isBefore(today)) throw invalid("Requested move-in date cannot be in the past.");
        return new ApplicationDetails(details.householdSize(), details.requestedMoveInDate(), details.message().trim());
    }
    private static void editable(RentalApplication application) { requireState(application, ApplicationStatus.DRAFT, ApplicationStatus.INFORMATION_REQUESTED); }
    private static void requireState(RentalApplication application, ApplicationStatus... allowed) {
        for (var status : allowed) if (application.status() == status) return;
        throw conflict("This action is unavailable while the application is " + application.status() + ".");
    }
    private static void requireOpen(Property property) { if (property.status() == PropertyStatus.ARCHIVED) throw conflict("Restore the property before continuing review."); }
    private static void version(long actual, long expected) { if (actual != expected) throw conflict("The application, profile, or listing has changed. Reload before continuing."); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "Application not found."); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
