package com.zaza.housing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RentalApplicationTests {
    private static final String PASSWORD = "Application-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void tenantProfileIsPrivateNormalizedAndVersioned() throws Exception {
        Account tenant = account(); Account other = account();
        call("GET", profilePath(), null, tenant.token, 404);
        call("POST", profilePath(), profile(" Tenant Person ", "ke", "kes", "120000.00"), tenant.token, 201);
        JsonNode result = node(call("GET", profilePath(), null, tenant.token, 200));
        assertThat(result.get("details").get("legalName").asText()).isEqualTo("Tenant Person");
        assertThat(result.get("details").get("countryCode").asText()).isEqualTo("KE");
        assertThat(result.get("details").get("incomeCurrency").asText()).isEqualTo("KES");
        assertThat(result.toString()).doesNotContain("password", "platformRole", "userId");
        call("POST", profilePath(), profile("Duplicate", "KE", "KES", "100"), tenant.token, 409);
        call("GET", profilePath(), null, other.token, 404);
        call("GET", profilePath(), null, null, 401);
        call("PUT", profilePath(), Map.of("version", 0, "details", profile("New name", "KE", "KES", "130000")), tenant.token, 200);
        call("PUT", profilePath(), Map.of("version", 0, "details", profile("Stale", "KE", "KES", "140000")), tenant.token, 409);
        var response = call("GET", profilePath(), null, tenant.token, 200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(node(response).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void invalidProfileFieldsAndIncompleteIncomeAreRejected() throws Exception {
        Account tenant = account();
        var body = new java.util.HashMap<>(profile("Person", "KE", "KES", "100"));
        body.put("countryCode", "ZZ"); call("POST", profilePath(), body, tenant.token, 400);
        body.put("countryCode", "KE"); body.put("incomeCurrency", "ZZZ"); call("POST", profilePath(), body, tenant.token, 400);
        body.put("incomeCurrency", "KES"); body.put("phone", "0712345678"); call("POST", profilePath(), body, tenant.token, 400);
        body.put("phone", "+254711111111"); body.remove("incomeCurrency"); call("POST", profilePath(), body, tenant.token, 400);
        body.remove("monthlyIncome"); call("POST", profilePath(), body, tenant.token, 201);
        body.put("legalName", " "); call("PUT", profilePath(), Map.of("version", 0, "details", body), tenant.token, 400);
        assertThat(node(call("GET", profilePath(), null, tenant.token, 200)).get("version").asInt()).isZero();
    }

    @Test
    void profileAuditFailureRollsBackCreation() throws Exception {
        Account tenant = account(); String constraint = constraint("profile");
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT " + constraint + " CHECK (action <> 'TENANT_PROFILE_CREATED') NOT VALID");
        try {
            call("POST", profilePath(), profile("Person", "KE", "KES", "100"), tenant.token, 409);
            call("GET", profilePath(), null, tenant.token, 404);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint); }
    }

    @Test
    void applicationRequiresCurrentPublicListingAndCompleteProfile() throws Exception {
        Inventory inventory = inventory(); Account tenant = account();
        String preview = "/api/v1/me/application-listings/" + inventory.listing;
        call("GET", preview, null, null, 401);
        JsonNode candidate = node(call("GET", preview, null, tenant.token, 200));
        assertThat(candidate.get("listingVersion").asInt()).isEqualTo(1);
        assertThat(candidate.toString()).doesNotContain("propertyId", "unitId", "organizationId", "Private address");
        call("POST", applicationsPath(), draftBody(inventory, 1), tenant.token, 409);
        createProfile(tenant);
        call("POST", applicationsPath(), draftBody(inventory, 0), tenant.token, 409);
        call("POST", applicationsPath(), Map.of("listingId", UUID.randomUUID(), "listingVersion", 1, "details", details()), tenant.token, 404);
        call("POST", inventory.listingPath() + "/withdraw", Map.of("version", 1), inventory.owner.token, 200);
        call("GET", preview, null, tenant.token, 404);
        call("POST", applicationsPath(), draftBody(inventory, 1), tenant.token, 404);
    }

    @Test
    void draftsAndWithdrawnUnsubmittedApplicationsStayHiddenFromStaff() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode draft = draft(inventory, tenant);
        String review = inventory.reviewPath(draft);
        call("GET", review, null, inventory.owner.token, 404);
        call("GET", review + "/history", null, inventory.owner.token, 404);
        call("GET", review + "/notes", null, inventory.owner.token, 404);
        assertThat(node(call("GET", inventory.reviewBase(), null, inventory.owner.token, 200)).size()).isZero();
        call("POST", ownPath(draft) + "/withdraw", Map.of("version", 0), tenant.token, 200);
        assertThat(node(call("GET", inventory.reviewBase() + "/summary", null, inventory.owner.token, 200)).get("total").asInt()).isZero();
        call("GET", review, null, inventory.owner.token, 404);
        assertThat(node(call("GET", applicationsPath() + "/summary", null, tenant.token, 200)).get("counts").get("WITHDRAWN").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateActiveApplicationsArePreventedEvenForConcurrentDrafts() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant();
        var one = client.sendAsync(request("POST", applicationsPath(), draftBody(inventory, 1), tenant.token), HttpResponse.BodyHandlers.ofString());
        var two = client.sendAsync(request("POST", applicationsPath(), draftBody(inventory, 1), tenant.token), HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{one.get(20, TimeUnit.SECONDS).statusCode(), two.get(20, TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(201, 409);
        assertThat(node(call("GET", applicationsPath(), null, tenant.token, 200)).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_applications WHERE applicant_id = ?", Integer.class, tenant.id)).isEqualTo(1);
    }

    @Test
    void submissionRequiresExplicitConsentAndCurrentProfileVersion() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode draft = draft(inventory, tenant);
        call("PUT", profilePath(), Map.of("version", 0, "details", profile("Latest profile", "KE", "KES", "500")), tenant.token, 200);
        call("POST", ownPath(draft) + "/submit", submitBody(0, 0, 1, true), tenant.token, 409);
        call("POST", ownPath(draft) + "/submit", submitBody(0, 1, 1, false), tenant.token, 400);
        var missing = new java.util.HashMap<>(submitBody(0, 1, 1, true)); missing.remove("consentToShare");
        call("POST", ownPath(draft) + "/submit", missing, tenant.token, 400);
        call("POST", ownPath(draft) + "/submit", submitBody(0, 1, 1, true), tenant.token, 200);
        JsonNode reviewed = node(call("GET", inventory.reviewPath(draft), null, inventory.owner.token, 200));
        assertThat(reviewed.get("applicant").get("details").get("legalName").asText()).isEqualTo("Latest profile");
        assertThat(reviewed.get("profileVersion").asInt()).isEqualTo(1);
    }

    @Test
    void fullInformationReviewApprovalWorkflowPreservesHistoryAndPrivateNotes() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = submitted(inventory, tenant);
        String staff = inventory.reviewPath(application); String own = ownPath(application);
        call("POST", staff + "/review", Map.of("version", 1), inventory.owner.token, 200);
        JsonNode note = node(call("POST", staff + "/notes", Map.of("version", 2, "text", "PRIVATE_REVIEWER_NOTE"), inventory.owner.token, 201));
        call("POST", staff + "/request-information", Map.of("version", 2, "message", "Please update household details."), inventory.owner.token, 200);
        var changed = new java.util.HashMap<>(details()); changed.put("householdSize", 3);
        call("PUT", own, Map.of("version", 3, "details", changed), tenant.token, 200);
        call("POST", own + "/submit", submitBody(4, 0, 1, true), tenant.token, 200);
        call("POST", staff + "/review", Map.of("version", 5), inventory.owner.token, 200);
        JsonNode approved = node(call("POST", staff + "/approve", Map.of("version", 6, "message", "Approved pending lease preparation."), inventory.owner.token, 200));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("version").asInt()).isEqualTo(7);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("RESERVED");
        call("GET", "/api/v1/listings/" + inventory.listing, null, null, 404);
        JsonNode history = node(call("GET", own + "/history", null, tenant.token, 200));
        assertThat(history.size()).isEqualTo(8);
        assertThat(history.toString()).doesNotContain("PRIVATE_REVIEWER_NOTE", inventory.owner.id.toString());
        assertThat(node(call("GET", own, null, tenant.token, 200)).toString()).doesNotContain("PRIVATE_REVIEWER_NOTE", "propertyId", "unitId");
        assertThat(node(call("GET", staff + "/notes", null, inventory.owner.token, 200)).get(0).get("id").asText()).isEqualTo(note.get("id").asText());
        assertThat(node(call("GET", "/api/v1/me/notifications/summary", null, tenant.token, 200)).get("unread").asInt()).isEqualTo(4);
        assertThat(node(call("GET", inventory.reviewBase() + "/summary", null, inventory.owner.token, 200)).get("counts").get("APPROVED").asInt()).isEqualTo(1);
    }

    @Test
    void submittedSnapshotDoesNotExposeLaterPrivateProfileEdits() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = submitted(inventory, tenant);
        call("PUT", profilePath(), Map.of("version", 0, "details", profile("PRIVATE_NEW_PROFILE", "KE", "KES", "999")), tenant.token, 200);
        String staff = inventory.reviewPath(application);
        assertThat(call("GET", staff, null, inventory.owner.token, 200).body()).doesNotContain("PRIVATE_NEW_PROFILE");
        call("POST", staff + "/request-information", Map.of("version", 1, "message", "Please review your profile."), inventory.owner.token, 200);
        call("POST", ownPath(application) + "/submit", submitBody(2, 1, 1, true), tenant.token, 200);
        assertThat(call("GET", staff, null, inventory.owner.token, 200).body()).contains("PRIVATE_NEW_PROFILE");
    }

    @Test
    void guestsOtherApplicantsAndOrdinaryMembersCannotReadOrDecideApplications() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); Account outsider = account(); Account member = account();
        JsonNode application = submitted(inventory, tenant);
        call("POST", "/api/v1/organizations/" + inventory.organization + "/members", Map.of("email", member.email, "role", "MEMBER"), inventory.owner.token, 201);
        call("GET", ownPath(application), null, outsider.token, 404);
        call("POST", ownPath(application) + "/withdraw", Map.of("version", 1), outsider.token, 404);
        call("GET", ownPath(application), null, null, 401);
        call("GET", inventory.reviewPath(application), null, member.token, 403);
        call("GET", inventory.reviewPath(application) + "/notes", null, member.token, 403);
        call("POST", inventory.reviewPath(application) + "/review", Map.of("version", 1), member.token, 403);
        call("GET", inventory.reviewPath(application), null, outsider.token, 404);
        String publicJson = call("GET", "/api/v1/listings/" + inventory.listing, null, null, 200).body();
        assertThat(publicJson).doesNotContain(tenant.email, "Tenant Person", "+254711111111", "monthlyIncome", "applicant");
    }

    @Test
    void foreignPropertyApplicationIdsCannotBeReviewed() throws Exception {
        Inventory first = inventory(); Inventory second = inventory(); Account tenant = tenant(); JsonNode application = submitted(first, tenant);
        String foreign = second.reviewBase() + "/" + application.get("id").asText();
        call("GET", foreign, null, second.owner.token, 404);
        call("POST", foreign + "/review", Map.of("version", 1), second.owner.token, 404);
        call("GET", foreign + "/history", null, second.owner.token, 404);
    }

    @Test
    void delegatedAgencyReviewAccessEndsWhenDelegationOrMembershipIsRevoked() throws Exception {
        Inventory inventory = inventory(); Account agency = account(); String organization = organization(agency, "AGENCY");
        call("PATCH", inventory.propertyPath() + "/management", Map.of("version", 1, "managerOrganizationId", organization), inventory.owner.token, 200);
        publish(inventory, 1, 2, 0);
        Account tenant = tenant(); JsonNode application = draft(inventory, tenant, 2); submit(application, tenant, 0, 0, 2);
        String staff = "/api/v1/organizations/" + organization + "/properties/" + inventory.property + "/applications/" + application.get("id").asText();
        call("POST", staff + "/review", Map.of("version", 1), agency.token, 200);
        Account admin = account();
        call("POST", "/api/v1/organizations/" + organization + "/members", Map.of("email", admin.email, "role", "ADMIN"), agency.token, 201);
        call("GET", staff, null, admin.token, 200);
        call("DELETE", "/api/v1/organizations/" + organization + "/members/" + admin.id, null, agency.token, 204);
        call("GET", staff, null, admin.token, 404);
        Map<String,Object> revoke = new java.util.HashMap<>(); revoke.put("version", 2); revoke.put("managerOrganizationId", null);
        call("PATCH", inventory.propertyPath() + "/management", revoke, inventory.owner.token, 200);
        call("GET", staff, null, agency.token, 404);
        call("POST", staff + "/reject", Map.of("version", 2, "message", "Denied"), agency.token, 404);
        call("GET", inventory.reviewPath(application), null, inventory.owner.token, 200);
    }

    @Test
    void invalidTransitionsAndStaleWritersAreRejected() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = draft(inventory, tenant);
        String own = ownPath(application); String staff = inventory.reviewPath(application);
        call("POST", staff + "/approve", Map.of("version", 0, "message", "Denied"), inventory.owner.token, 404);
        submit(application, tenant, 0, 0, 1);
        call("PUT", own, Map.of("version", 1, "details", details()), tenant.token, 409);
        call("POST", own + "/submit", submitBody(1, 0, 1, true), tenant.token, 409);
        call("POST", staff + "/approve", Map.of("version", 1, "message", "Premature"), inventory.owner.token, 409);
        call("POST", staff + "/review", Map.of("version", 0), inventory.owner.token, 409);
        call("POST", staff + "/review", Map.of("version", 1), inventory.owner.token, 200);
        call("POST", staff + "/reject", Map.of("version", 2, "message", "No longer proceeding."), inventory.owner.token, 200);
        call("POST", own + "/withdraw", Map.of("version", 3), tenant.token, 409);
        draft(inventory, tenant);
    }

    @Test
    void changedListingTermsRequireApplicantRefreshAndResubmission() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = submitted(inventory, tenant);
        String staff = inventory.reviewPath(application); String own = ownPath(application);
        call("POST", staff + "/review", Map.of("version", 1), inventory.owner.token, 200);
        var newUnit = new java.util.HashMap<>(unitDetails()); newUnit.put("monthlyRent", "31000");
        call("PUT", inventory.unitPath(), Map.of("version", 0, "details", newUnit), inventory.owner.token, 200);
        publish(inventory, 1, 1, 1);
        call("POST", staff + "/approve", Map.of("version", 2, "message", "Approve"), inventory.owner.token, 409);
        call("POST", own + "/refresh-listing", Map.of("version", 2, "listingVersion", 2), tenant.token, 409);
        call("POST", staff + "/request-information", Map.of("version", 2, "message", "Please review the new listing terms."), inventory.owner.token, 200);
        JsonNode refreshed = node(call("POST", own + "/refresh-listing", Map.of("version", 3, "listingVersion", 2), tenant.token, 200));
        assertThat(refreshed.get("listing").get("monthlyRent").decimalValue()).isEqualByComparingTo("31000");
        call("POST", own + "/submit", submitBody(4, 0, 2, true), tenant.token, 200);
        call("POST", staff + "/review", Map.of("version", 5), inventory.owner.token, 200);
        call("POST", staff + "/approve", Map.of("version", 6, "message", "Approved current terms."), inventory.owner.token, 200);
    }

    @Test
    void unavailableListingsCannotBeSubmittedOrApprovedButCanBeRejected() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = draft(inventory, tenant);
        call("POST", inventory.listingPath() + "/withdraw", Map.of("version", 1), inventory.owner.token, 200);
        call("POST", ownPath(application) + "/submit", submitBody(0, 0, 1, true), tenant.token, 409);
        call("POST", ownPath(application) + "/withdraw", Map.of("version", 0), tenant.token, 200);
        publish(inventory, 2, 1, 0);
        JsonNode next = draft(inventory, tenant, 3); submit(next, tenant, 0, 0, 3);
        call("PATCH", inventory.propertyPath() + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 200);
        call("POST", inventory.reviewPath(next) + "/review", Map.of("version", 1), inventory.owner.token, 409);
        call("POST", inventory.reviewPath(next) + "/reject", Map.of("version", 1, "message", "Property unavailable."), inventory.owner.token, 200);
    }

    @Test
    void competingApprovalsReserveExactlyOneUnit() throws Exception {
        Inventory inventory = inventory(); Account one = tenant(); Account two = tenant();
        JsonNode first = submitted(inventory, one); JsonNode second = submitted(inventory, two);
        call("POST", inventory.reviewPath(first) + "/review", Map.of("version", 1), inventory.owner.token, 200);
        call("POST", inventory.reviewPath(second) + "/review", Map.of("version", 1), inventory.owner.token, 200);
        var a = client.sendAsync(request("POST", inventory.reviewPath(first) + "/approve", Map.of("version", 2, "message", "Approved"), inventory.owner.token), HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(request("POST", inventory.reviewPath(second) + "/approve", Map.of("version", 2, "message", "Approved"), inventory.owner.token), HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{a.get(20, TimeUnit.SECONDS).statusCode(), b.get(20, TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE unit_id = ?", Integer.class, UUID.fromString(inventory.unit))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_applications WHERE unit_id = ? AND status = 'APPROVED'", Integer.class, UUID.fromString(inventory.unit))).isEqualTo(1);
        assertThat(unit(inventory).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void cancellationReleasesOnlyItsReservationAndDoesNotRepublishListing() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = approved(inventory, tenant);
        call("PUT", inventory.unitPath(), Map.of("version", 1, "details", unitDetails()), inventory.owner.token, 409);
        call("PATCH", inventory.unitPath() + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 409);
        call("POST", inventory.reviewPath(application) + "/cancel-approval", Map.of("version", 3, "message", "Approval cancelled before lease."), inventory.owner.token, 200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(unit(inventory).get("version").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE unit_id = ?", Integer.class, UUID.fromString(inventory.unit))).isZero();
        call("GET", "/api/v1/listings/" + inventory.listing, null, null, 404);
        call("POST", inventory.reviewPath(application) + "/cancel-approval", Map.of("version", 4, "message", "Again"), inventory.owner.token, 409);
        publish(inventory, 1, 1, 2);
        draft(inventory, tenant, 2);
    }

    @Test
    void applicantWithdrawalReleasesAnApprovedReservation() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = approved(inventory, tenant);
        call("POST", ownPath(application) + "/withdraw", Map.of("version", 3), tenant.token, 200);
        assertThat(node(call("GET", ownPath(application), null, tenant.token, 200)).get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
        call("GET", "/api/v1/listings/" + inventory.listing, null, null, 404);
    }

    @Test
    void archivedPropertyStillAllowsApprovalCancellationAndSafeRelease() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = approved(inventory, tenant);
        call("PATCH", inventory.propertyPath() + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 200);
        call("POST", inventory.reviewPath(application) + "/cancel-approval", Map.of("version", 3, "message", "Property archived."), inventory.owner.token, 200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
        call("GET", "/api/v1/listings/" + inventory.listing, null, null, 404);
    }

    @Test
    void auditFailureRollsBackApprovalReservationHistoryAndNotification() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = underReview(inventory, tenant);
        String constraint = constraint("approval");
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT " + constraint + " CHECK (action <> 'APPLICATION_APPROVED') NOT VALID");
        try {
            call("POST", inventory.reviewPath(application) + "/approve", Map.of("version", 2, "message", "Approved"), inventory.owner.token, 409);
            assertApprovalRolledBack(inventory, tenant, application);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint); }
    }

    @Test
    void notificationFailureRollsBackTheEntireApproval() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = underReview(inventory, tenant);
        String constraint = constraint("notification");
        jdbc.execute("ALTER TABLE in_app_notifications ADD CONSTRAINT " + constraint + " CHECK (type <> 'APPROVED') NOT VALID");
        try {
            call("POST", inventory.reviewPath(application) + "/approve", Map.of("version", 2, "message", "Approved"), inventory.owner.token, 409);
            assertApprovalRolledBack(inventory, tenant, application);
        } finally { jdbc.execute("ALTER TABLE in_app_notifications DROP CONSTRAINT " + constraint); }
    }

    @Test
    void applicationHistoryAndReviewerNotesAreAppendOnly() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = submitted(inventory, tenant);
        JsonNode note = node(call("POST", inventory.reviewPath(application) + "/notes", Map.of("version", 1, "text", "Private note"), inventory.owner.token, 201));
        assertThatThrownBy(() -> jdbc.update("UPDATE application_events SET message = 'changed' WHERE application_id = ?", id(application))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM application_events WHERE application_id = ?", id(application))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM application_reviewer_notes WHERE id = ?", id(note))).isInstanceOf(RuntimeException.class);
        assertThat(node(call("GET", ownPath(application) + "/history", null, tenant.token, 200)).size()).isEqualTo(2);
        assertThat(node(call("GET", inventory.reviewPath(application) + "/notes", null, inventory.owner.token, 200)).size()).isEqualTo(1);
    }

    @Test
    void notificationsArePersonalPaginatedAndMarkReadIsIdempotent() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); Account other = account(); approved(inventory, tenant);
        String base = "/api/v1/me/notifications";
        JsonNode inbox = node(call("GET", base + "?unreadOnly=true&limit=1", null, tenant.token, 200));
        assertThat(inbox.size()).isEqualTo(1); JsonNode first = inbox.get(0);
        call("PATCH", base + "/" + first.get("id").asText() + "/read", null, other.token, 404);
        JsonNode read = node(call("PATCH", base + "/" + first.get("id").asText() + "/read", null, tenant.token, 200));
        JsonNode again = node(call("PATCH", base + "/" + first.get("id").asText() + "/read", null, tenant.token, 200));
        assertThat(again.get("readAt").asText()).isEqualTo(read.get("readAt").asText());
        assertThat(node(call("GET", base + "/summary", null, tenant.token, 200)).get("unread").asInt()).isEqualTo(1);
        assertThat(node(call("GET", base, null, other.token, 200)).size()).isZero();
        call("GET", base + "?limit=0", null, tenant.token, 400);
        call("GET", base, null, null, 401);
    }

    @Test
    void validationPaginationAndStatusFiltersRespectScope() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant();
        var details = new java.util.HashMap<>(details()); details.put("householdSize", 0);
        call("POST", applicationsPath(), Map.of("listingId", inventory.listing, "listingVersion", 1, "details", details), tenant.token, 400);
        details.put("householdSize", 2); details.put("requestedMoveInDate", LocalDate.now(clock).minusDays(1).toString());
        call("POST", applicationsPath(), Map.of("listingId", inventory.listing, "listingVersion", 1, "details", details), tenant.token, 400);
        JsonNode application = submitted(inventory, tenant);
        call("POST", inventory.reviewPath(application) + "/reject", Map.of("version", 1, "message", " "), inventory.owner.token, 400);
        call("GET", applicationsPath() + "?limit=101", null, tenant.token, 400);
        call("GET", inventory.reviewBase() + "?offset=-1", null, inventory.owner.token, 400);
        call("GET", applicationsPath() + "?status=INVALID", null, tenant.token, 400);
        assertThat(node(call("GET", applicationsPath() + "?status=SUBMITTED&limit=1", null, tenant.token, 200)).size()).isEqualTo(1);
        assertThat(node(call("GET", applicationsPath() + "?status=DRAFT", null, tenant.token, 200)).size()).isZero();
        assertThat(node(call("GET", applicationsPath() + "/summary", null, tenant.token, 200)).get("counts").get("SUBMITTED").asInt()).isEqualTo(1);
    }

    @Test
    void disabledApplicantsCannotBeApproved() throws Exception {
        Inventory inventory = inventory(); Account tenant = tenant(); JsonNode application = underReview(inventory, tenant);
        jdbc.update("UPDATE app_users SET active = false WHERE id = ?", tenant.id);
        call("POST", inventory.reviewPath(application) + "/approve", Map.of("version", 2, "message", "Approved"), inventory.owner.token, 409);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
        call("GET", ownPath(application), null, tenant.token, 401);
    }

    @Test
    void reviewersCannotApproveTheirOwnApplication() throws Exception {
        Inventory inventory = inventory(); createProfile(inventory.owner);
        JsonNode application = submitted(inventory, inventory.owner);
        call("POST", inventory.reviewPath(application) + "/review", Map.of("version", 1), inventory.owner.token, 403);
        call("POST", inventory.reviewPath(application) + "/approve", Map.of("version", 1, "message", "Self approval"), inventory.owner.token, 403);
    }

    private void assertApprovalRolledBack(Inventory inventory, Account tenant, JsonNode application) throws Exception {
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(unit(inventory).get("version").asInt()).isZero();
        JsonNode current = node(call("GET", ownPath(application), null, tenant.token, 200));
        assertThat(current.get("status").asText()).isEqualTo("UNDER_REVIEW"); assertThat(current.get("version").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE application_id = ?", Integer.class, id(application))).isZero();
        assertThat(node(call("GET", ownPath(application) + "/history", null, tenant.token, 200)).size()).isEqualTo(3);
        assertThat(node(call("GET", "/api/v1/me/notifications", null, tenant.token, 200)).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'APPLICATION_APPROVED'", Integer.class, id(application))).isZero();
    }
    private String constraint(String type) { return "application_test_" + type + "_" + UUID.randomUUID().toString().replace("-", ""); }
    private UUID id(JsonNode node) { return UUID.fromString(node.get("id").asText()); }
    private Map<String,Object> profile(String name, String country, String currency, String income) {
        return Map.of("legalName", name, "phone", "+254711111111", "countryCode", country, "city", "Nairobi", "employmentStatus", "EMPLOYED", "monthlyIncome", income, "incomeCurrency", currency);
    }
    private String profilePath() { return "/api/v1/me/tenant-profile"; }
    private String applicationsPath() { return "/api/v1/me/applications"; }
    private String ownPath(JsonNode application) { return applicationsPath() + "/" + application.get("id").asText(); }
    private Map<String,Object> details() { return Map.of("householdSize", 2, "requestedMoveInDate", LocalDate.now(clock).plusDays(14).toString(), "message", "Interested in this property."); }
    private Map<String,Object> draftBody(Inventory inventory, long listingVersion) { return Map.of("listingId", inventory.listing, "listingVersion", listingVersion, "details", details()); }
    private Map<String,Object> submitBody(long version, long profile, long listing, boolean consent) { return Map.of("version", version, "profileVersion", profile, "listingVersion", listing, "consentToShare", consent); }
    private void createProfile(Account tenant) throws Exception { call("POST", profilePath(), profile("Tenant Person", "KE", "KES", "120000"), tenant.token, 201); }
    private Account tenant() throws Exception { Account tenant = account(); createProfile(tenant); return tenant; }
    private JsonNode draft(Inventory inventory, Account tenant) throws Exception { return draft(inventory, tenant, 1); }
    private JsonNode draft(Inventory inventory, Account tenant, long listingVersion) throws Exception { return node(call("POST", applicationsPath(), draftBody(inventory, listingVersion), tenant.token, 201)); }
    private void submit(JsonNode application, Account tenant, long version, long profile, long listing) throws Exception { call("POST", ownPath(application) + "/submit", submitBody(version, profile, listing, true), tenant.token, 200); }
    private JsonNode submitted(Inventory inventory, Account tenant) throws Exception { JsonNode application = draft(inventory, tenant); submit(application, tenant, 0, 0, 1); return application; }
    private JsonNode underReview(Inventory inventory, Account tenant) throws Exception { JsonNode application = submitted(inventory, tenant); call("POST", inventory.reviewPath(application) + "/review", Map.of("version", 1), inventory.owner.token, 200); return application; }
    private JsonNode approved(Inventory inventory, Account tenant) throws Exception { JsonNode application = underReview(inventory, tenant); call("POST", inventory.reviewPath(application) + "/approve", Map.of("version", 2, "message", "Approved pending lease preparation."), inventory.owner.token, 200); return application; }
    private JsonNode unit(Inventory inventory) throws Exception { return node(call("GET", inventory.unitPath(), null, inventory.owner.token, 200)); }
    private Map<String,Object> unitDetails() { return Map.of("label", "A", "bedrooms", 2, "bathrooms", 1, "monthlyRent", "25000", "deposit", "50000", "currency", "KES", "availability", "AVAILABLE"); }
    private void publish(Inventory inventory, long version, long propertyVersion, long unitVersion) throws Exception {
        call("POST", inventory.listingPath() + "/publish", Map.of("version", version, "propertyVersion", propertyVersion, "unitVersion", unitVersion), inventory.owner.token, 200);
    }
    private Inventory inventory() throws Exception {
        Account owner = account(); String organization = organization(owner, "LANDLORD");
        String base = "/api/v1/organizations/" + organization + "/properties";
        String property = node(call("POST", base, Map.of("name", "Application property", "type", "HOUSE", "countryCode", "KE", "city", "Nairobi", "address", "Private address"), owner.token, 201)).get("id").asText();
        String propertyPath = base + "/" + property;
        call("PATCH", propertyPath + "/status", Map.of("version", 0, "status", "ACTIVE"), owner.token, 200);
        String unit = node(call("POST", propertyPath + "/units", unitDetails(), owner.token, 201)).get("id").asText();
        String listing = node(call("POST", propertyPath + "/listings", Map.of("unitId", unit, "details", Map.of("title", "Application rental", "description", "Public rental offer", "showExactLocation", false)), owner.token, 201)).get("id").asText();
        Inventory inventory = new Inventory(owner, organization, property, unit, listing); publish(inventory, 0, 1, 0); return inventory;
    }
    private String organization(Account actor, String type) throws Exception { return node(call("POST", "/api/v1/organizations", Map.of("name", "Application organization", "type", type), actor.token, 201)).get("id").asText(); }
    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode user = node(call("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Application test"), null, 201));
        JsonNode login = node(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200));
        return new Account(UUID.fromString(user.get("id").asText()), email, login.get("accessToken").asText());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpRequest request(String method, String path, Object body, String token) {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(25));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private HttpResponse<String> call(String method, String path, Object body, String token, int status) throws Exception {
        var response = client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status); return response;
    }
    private record Account(UUID id, String email, String token) { }
    private record Inventory(Account owner, String organization, String property, String unit, String listing) {
        String propertyPath() { return "/api/v1/organizations/" + organization + "/properties/" + property; }
        String unitPath() { return propertyPath() + "/units/" + unit; }
        String listingPath() { return propertyPath() + "/listings/" + listing; }
        String reviewBase() { return propertyPath() + "/applications"; }
        String reviewPath(JsonNode application) { return reviewBase() + "/" + application.get("id").asText(); }
    }
}
