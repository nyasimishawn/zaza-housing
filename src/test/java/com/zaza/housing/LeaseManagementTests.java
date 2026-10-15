package com.zaza.housing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LeaseManagementTests {
    private static final String PASSWORD="Lease-test-password-42";
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LeaseTestClock clock;
    @BeforeEach void resetClock() { clock.now=Instant.now(); }

    @Test
    void draftRequiresApprovedApplicationAndItsCurrentReservation() throws Exception {
        Inventory inventory=inventory();
        call("POST",inventory.base(),Map.of("applicationId",inventory.application,"applicationVersion",2,"terms",terms()),inventory.owner,409);
        call("POST",inventory.base(),Map.of("applicationId",UUID.randomUUID(),"applicationVersion",3,"terms",terms()),inventory.owner,404);
        JsonNode lease=draft(inventory);
        assertThat(lease.get("version").asInt()).isZero();
        assertThat(lease.get("revision").get("number").asInt()).isEqualTo(1);
        assertThat(lease.get("revision").get("parties").get("lessorName").asText()).isEqualTo("Lease business");
        call("POST",inventory.base(),Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",terms()),inventory.owner,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_leases WHERE application_id=?",Integer.class,UUID.fromString(inventory.application))).isEqualTo(1);
    }

    @Test
    void initialDraftAndItsExactDocumentStayHiddenFromTenant() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=draft(inventory);
        call("GET",own(lease),null,inventory.tenant,404);
        call("GET",own(lease)+"/revisions",null,inventory.tenant,404);
        call("GET",own(lease)+"/revisions/"+revision(lease)+"/document",null,inventory.tenant,404);
        assertThat(node(call("GET","/api/v1/me/leases",null,inventory.tenant,200)).size()).isZero();
        call("POST",inventory.path(lease)+"/send",version(lease),inventory.owner,200);
        JsonNode shared=tenant(inventory,lease);
        assertThat(shared.get("revision").get("parties").get("propertyAddress").asText()).isEqualTo("Private lease premises");
        assertThat(shared.toString()).doesNotContain("monthlyIncome","employmentStatus","ownerOrganizationId","unitId","propertyId");
    }

    @Test
    void downloadedUtf8BytesMatchTheStoredDigestAndAreNotCached() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        var response=client.send(request("GET",own(lease)+"/revisions/"+revision(lease)+"/document",null,inventory.tenant),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
        assertThat(hash).isEqualTo(lease.get("revision").get("documentHash").asText());
        assertThat(new String(response.body(),StandardCharsets.UTF_8)).contains("caf\u00e9","Tenant Person","KES 25000.00");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/plain","UTF-8");
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
    }

    @Test
    void fullSigningActivationCompletionAndVacancyClearanceWork() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=executed(inventory);
        assertThat(lease.get("status").asText()).isEqualTo("SIGNED");
        assertThat(lease.get("signatures").size()).isEqualTo(2);
        assertThat(app(inventory).get("status").asText()).isEqualTo("LEASED");
        JsonNode receipt=node(call("GET",own(lease)+"/revisions/"+revision(lease)+"/receipt",null,inventory.tenant,200));
        assertThat(receipt.get("signatures").size()).isEqualTo(2);
        assertThat(receipt.toString()).doesNotContain("password","monthlyIncome");
        lease=node(call("POST",inventory.path(lease)+"/activate",Map.of("version",lease.get("version").asLong(),"possessionConfirmed",true),inventory.owner,200));
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE application_id=?",Integer.class,UUID.fromString(inventory.application))).isZero();
        clock.days(31); login(inventory.owner);
        lease=node(call("POST",inventory.path(lease)+"/complete",reason(lease,"Lease term ended."),inventory.owner,200));
        assertThat(lease.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(app(inventory).get("status").asText()).isEqualTo("CLOSED");
        call("PUT",inventory.unitPath(),Map.of("version",3,"details",unitDetails()),inventory.owner,409);
        lease=node(call("POST",inventory.path(lease)+"/release-unit",Map.of("version",lease.get("version").asLong(),"vacancyConfirmed",true,"message","Inspection confirms vacancy."),inventory.owner,200));
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(unit(inventory).get("version").asInt()).isEqualTo(4);
        assertThat(lease.get("unitReleasedAt").isNull()).isFalse();
        call("GET","/api/v1/listings/"+inventory.listing,null,null,404);
    }

    @Test
    void ownerCanSignFirstAndDuplicatePartySignaturesAreRejected() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        lease=sign(inventory,lease,false);
        assertThat(lease.get("status").asText()).isEqualTo("SENT");
        call("POST",inventory.path(lease)+"/sign",signBody(lease,false),inventory.owner,409);
        lease=sign(inventory,lease,true);
        assertThat(lease.get("status").asText()).isEqualTo("SIGNED");
        assertThat(lease.get("signatures").size()).isEqualTo(2);
    }

    @Test
    void signingRequiresPasswordNameConsentDigestAndCurrentRevision() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        var body=new HashMap<>(signBody(lease,true));
        body.put("evidence",evidence(lease,true,"0".repeat(64),PASSWORD,"Tenant Person",true));
        call("POST",own(lease)+"/sign",body,inventory.tenant,409);
        body.put("evidence",evidence(lease,true,hash(lease),"Wrong-password-42","Tenant Person",true));
        call("POST",own(lease)+"/sign",body,inventory.tenant,401);
        body.put("evidence",evidence(lease,true,hash(lease),PASSWORD,"Different Person",true));
        call("POST",own(lease)+"/sign",body,inventory.tenant,400);
        body.put("evidence",evidence(lease,true,hash(lease),PASSWORD,"Tenant Person",false));
        call("POST",own(lease)+"/sign",body,inventory.tenant,400);
        body.put("evidence",evidence(lease,true,hash(lease),PASSWORD,"Tenant Person",true)); body.put("revisionId",UUID.randomUUID());
        call("POST",own(lease)+"/sign",body,inventory.tenant,409);
        assertThat(tenant(inventory,lease).get("signatures").size()).isZero();
    }

    @Test
    void recallingNeverCarriesOldSignaturesIntoNewPrivateDrafts() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory); String oldRevision=revision(lease);
        lease=sign(inventory,lease,true);
        lease=node(call("POST",inventory.path(lease)+"/recall",reason(lease,"Please review revised terms."),inventory.owner,200));
        var terms=new HashMap<>(terms()); terms.put("agreementText","PRIVATE_DRAFT_CONTENT requiring a new review and consent.");
        lease=node(call("PUT",inventory.path(lease),Map.of("version",lease.get("version").asLong(),"terms",terms),inventory.owner,200));
        JsonNode view=tenant(inventory,lease);
        assertThat(view.toString()).doesNotContain("PRIVATE_DRAFT_CONTENT");
        assertThat(view.get("revision").get("id").asText()).isEqualTo(oldRevision);
        call("GET",own(lease)+"/revisions/"+revision(lease)+"/document",null,inventory.tenant,404);
        call("POST",own(lease)+"/sign",signBody(lease,true),inventory.tenant,409);
        lease=node(call("POST",inventory.path(lease)+"/send",version(lease),inventory.owner,200));
        assertThat(tenant(inventory,lease).get("signatures").size()).isZero();
        assertThat(node(call("GET",own(lease)+"/revisions",null,inventory.tenant,200)).size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lease_signatures WHERE revision_id=?",Integer.class,UUID.fromString(oldRevision))).isEqualTo(1);
    }

    @Test
    void fullySignedAgreementsCannotBeEditedRecalledOrUnilaterallyCancelled() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=executed(inventory);
        call("PUT",inventory.path(lease),Map.of("version",lease.get("version").asLong(),"terms",terms()),inventory.owner,409);
        call("POST",inventory.path(lease)+"/recall",reason(lease,"Recall"),inventory.owner,409);
        call("POST",inventory.path(lease)+"/cancel",reason(lease,"Cancel"),inventory.owner,409);
        call("POST",own(lease)+"/decline",reason(lease,"Decline"),inventory.tenant,409);
        call("POST","/api/v1/me/applications/"+inventory.application+"/withdraw",Map.of("version",4),inventory.tenant,409);
        call("POST",inventory.propertyPath()+"/applications/"+inventory.application+"/cancel-approval",Map.of("version",4,"message","Cancel"),inventory.owner,409);
    }

    @Test
    void applicationReservationCannotBeReleasedWhileUnsignedLeaseHoldsIt() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        call("POST","/api/v1/me/applications/"+inventory.application+"/withdraw",Map.of("version",3),inventory.tenant,409);
        call("POST",inventory.propertyPath()+"/applications/"+inventory.application+"/cancel-approval",Map.of("version",3,"message","Cancel"),inventory.owner,409);
        call("POST",inventory.path(lease)+"/cancel",reason(lease,"Prepare another agreement later."),inventory.owner,200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("RESERVED");
        call("POST","/api/v1/me/applications/"+inventory.application+"/withdraw",Map.of("version",3),inventory.tenant,200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void unsignedTenantDeclineRetainsReviewableHistoryAndAllowsRedrafting() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        call("POST",own(lease)+"/decline",reason(lease,"Please prepare different terms."),inventory.tenant,200);
        assertThat(tenant(inventory,lease).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("RESERVED");
        JsonNode replacement=draft(inventory);
        assertThat(replacement.get("id").asText()).isNotEqualTo(lease.get("id").asText());
    }

    @Test
    void guestsUnrelatedAccountsAndOrdinaryMembersCannotReadOrSign() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory); Account other=account(); Account member=account();
        call("POST","/api/v1/organizations/"+inventory.organization+"/members",Map.of("email",member.email,"role","MEMBER"),inventory.owner,201);
        call("GET",own(lease),null,null,401);
        call("GET",own(lease),null,other,404);
        call("GET",inventory.path(lease),null,other,404);
        call("GET",inventory.path(lease),null,member,403);
        call("POST",inventory.path(lease)+"/sign",signBody(lease,false),member,403);
        call("GET",own(lease)+"/revisions/"+revision(lease)+"/document",null,other,404);
        call("GET",inventory.path(lease)+"/history",null,member,403);
    }

    @Test
    void agenciesCanPrepareLeasesButOnlyOwnerOrganizationCanSignForLessor() throws Exception {
        Inventory inventory=inventory(); Account agency=account(); String organization=organization(agency,"AGENCY");
        call("PATCH",inventory.propertyPath()+"/management",Map.of("version",1,"managerOrganizationId",organization),inventory.owner,200);
        String base="/api/v1/organizations/"+organization+"/properties/"+inventory.property+"/leases";
        JsonNode lease=node(call("POST",base,Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",terms()),agency,201));
        String path=base+"/"+lease.get("id").asText();
        lease=node(call("POST",path+"/send",version(lease),agency,200));
        call("POST",path+"/sign",signBody(lease,false),agency,403);
        lease=sign(inventory,lease,true); lease=sign(inventory,lease,false);
        assertThat(lease.get("status").asText()).isEqualTo("SIGNED");
        var revoke=new HashMap<String,Object>(); revoke.put("version",2); revoke.put("managerOrganizationId",null);
        call("PATCH",inventory.propertyPath()+"/management",revoke,inventory.owner,200);
        call("GET",path,null,agency,404);
        call("GET",inventory.path(lease),null,inventory.owner,200);
    }

    @Test
    void concurrentLeaseCreationLeavesOneDraftAndOneRevision() throws Exception {
        Inventory inventory=inventory(); var body=Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",terms());
        var first=client.sendAsync(request("POST",inventory.base(),body,inventory.owner),HttpResponse.BodyHandlers.ofString());
        var second=client.sendAsync(request("POST",inventory.base(),body,inventory.owner),HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{first.get(20,TimeUnit.SECONDS).statusCode(),second.get(20,TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_leases WHERE application_id=?",Integer.class,UUID.fromString(inventory.application))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lease_revisions r JOIN rental_leases l ON l.id=r.lease_id WHERE l.application_id=?",Integer.class,UUID.fromString(inventory.application))).isEqualTo(1);
    }

    @Test
    void concurrentRevisionEditsRejectTheStaleWriter() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=draft(inventory); var body=Map.of("version",0,"terms",terms());
        var first=client.sendAsync(request("PUT",inventory.path(lease),body,inventory.owner),HttpResponse.BodyHandlers.ofString());
        var second=client.sendAsync(request("PUT",inventory.path(lease),body,inventory.owner),HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{first.get(20,TimeUnit.SECONDS).statusCode(),second.get(20,TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lease_revisions WHERE lease_id=?",Integer.class,id(lease))).isEqualTo(2);
    }

    @Test
    void concurrentSignersReloadWithoutProducingDuplicateEvidence() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        var tenant=client.sendAsync(request("POST",own(lease)+"/sign",signBody(lease,true),inventory.tenant),HttpResponse.BodyHandlers.ofString());
        var owner=client.sendAsync(request("POST",inventory.path(lease)+"/sign",signBody(lease,false),inventory.owner),HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{tenant.get(20,TimeUnit.SECONDS).statusCode(),owner.get(20,TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(200,409);
        lease=manager(inventory,lease);
        boolean tenantMissing=!lease.get("signatures").get(0).get("party").asText().equals("TENANT");
        lease=sign(inventory,lease,tenantMissing);
        assertThat(lease.get("status").asText()).isEqualTo("SIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lease_signatures WHERE lease_id=?",Integer.class,id(lease))).isEqualTo(2);
    }

    @Test
    void signatureAuditFailureRollsBackEvidenceAndState() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory); String constraint=constraint();
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT "+constraint+" CHECK (action <> 'LEASE_SIGNATURE_RECORDED') NOT VALID");
        try {
            call("POST",own(lease)+"/sign",signBody(lease,true),inventory.tenant,409);
            assertThat(manager(inventory,lease).get("version").asInt()).isEqualTo(1);
            assertThat(manager(inventory,lease).get("signatures").size()).isZero();
            assertThat(app(inventory).get("status").asText()).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lease_events WHERE lease_id=?",Integer.class,id(lease))).isEqualTo(2);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT "+constraint); }
    }

    @Test
    void notificationFailureRollsBackFinalSignatureAndApplicationConversion() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sign(inventory,sent(inventory),false); String constraint=constraint();
        jdbc.execute("ALTER TABLE in_app_notifications ADD CONSTRAINT "+constraint+" CHECK (type <> 'TENANT_SIGNED') NOT VALID");
        try {
            call("POST",own(lease)+"/sign",signBody(lease,true),inventory.tenant,409);
            JsonNode current=manager(inventory,lease);
            assertThat(current.get("status").asText()).isEqualTo("SENT");
            assertThat(current.get("version").asInt()).isEqualTo(2);
            assertThat(current.get("signatures").size()).isEqualTo(1);
            assertThat(app(inventory).get("status").asText()).isEqualTo("APPROVED");
        } finally { jdbc.execute("ALTER TABLE in_app_notifications DROP CONSTRAINT "+constraint); }
    }

    @Test
    void activationAuditFailureRestoresReservationAndUnit() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=executed(inventory); String constraint=constraint();
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT "+constraint+" CHECK (action <> 'LEASE_UNIT_OCCUPIED') NOT VALID");
        try {
            call("POST",inventory.path(lease)+"/activate",Map.of("version",lease.get("version").asLong(),"possessionConfirmed",true),inventory.owner,409);
            assertThat(manager(inventory,lease).get("status").asText()).isEqualTo("SIGNED");
            assertThat(unit(inventory).get("availability").asText()).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE application_id=?",Integer.class,UUID.fromString(inventory.application))).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT "+constraint); }
    }

    @Test
    void agreementRevisionsSignaturesSharesAndEventsAreImmutable() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=executed(inventory); UUID revision=UUID.fromString(revision(lease));
        assertThatThrownBy(()->jdbc.update("UPDATE lease_revisions SET document='changed' WHERE id=?",revision)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE lease_signatures SET typed_name='changed' WHERE revision_id=?",revision)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM lease_revision_shares WHERE revision_id=?",revision)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM lease_events WHERE lease_id=?",id(lease))).isInstanceOf(RuntimeException.class);
        assertThat(manager(inventory,lease).get("signatures").size()).isEqualTo(2);
    }

    @Test
    void validationAndStaleVersionsProtectDrafts() throws Exception {
        Inventory inventory=inventory(); var bad=new HashMap<>(terms()); bad.put("endDate",today().toString());
        call("POST",inventory.base(),Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",bad),inventory.owner,400);
        bad=new HashMap<>(terms()); bad.put("currency","ZZZ");
        call("POST",inventory.base(),Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",bad),inventory.owner,400);
        bad=new HashMap<>(terms()); bad.put("rentDueDay",31);
        call("POST",inventory.base(),Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",bad),inventory.owner,400);
        JsonNode lease=draft(inventory);
        call("PUT",inventory.path(lease),Map.of("version",4,"terms",terms()),inventory.owner,409);
        call("POST",inventory.path(lease)+"/send",Map.of("version",4),inventory.owner,409);
        call("GET",inventory.base()+"?limit=0",null,inventory.owner,400);
        call("GET","/api/v1/me/leases?status=UNKNOWN",null,inventory.tenant,400);
    }

    @Test
    void completionAndActivationRespectDatesAndConfirmations() throws Exception {
        Inventory inventory=inventory(); var future=new HashMap<>(terms()); future.put("startDate",today().plusDays(5).toString());
        JsonNode lease=draft(inventory,future); lease=send(inventory,lease); lease=sign(inventory,lease,true); lease=sign(inventory,lease,false);
        call("POST",inventory.path(lease)+"/activate",Map.of("version",lease.get("version").asLong(),"possessionConfirmed",true),inventory.owner,409);
        call("POST",inventory.path(lease)+"/activate",Map.of("version",lease.get("version").asLong(),"possessionConfirmed",false),inventory.owner,400);
        call("POST",inventory.path(lease)+"/complete",reason(lease,"Not yet"),inventory.owner,409);
    }

    @Test
    void inactivePropertyAndDisabledTenantBlockNewExecution() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory);
        call("PATCH",inventory.propertyPath()+"/status",Map.of("version",1,"status","ARCHIVED"),inventory.owner,200);
        call("POST",own(lease)+"/sign",signBody(lease,true),inventory.tenant,409);
        call("PATCH",inventory.propertyPath()+"/status",Map.of("version",2,"status","ACTIVE"),inventory.owner,200);
        jdbc.update("UPDATE app_users SET active=false WHERE id=?",inventory.tenant.id);
        call("POST",inventory.path(lease)+"/sign",signBody(lease,false),inventory.owner,409);
        call("POST",own(lease)+"/sign",signBody(lease,true),inventory.tenant,401);
    }

    @Test
    void partialSignatureDoesNotProduceExecutionReceipt() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory); lease=sign(inventory,lease,true);
        call("GET",own(lease)+"/revisions/"+revision(lease)+"/receipt",null,inventory.tenant,409);
    }

    @Test
    void activeLeaseRequiresMutualTerminationAndExplicitCheckout() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=active(inventory);
        lease=requestEnd(inventory,lease,true,today());
        String ending=lease.get("termination").get("id").asText();
        assertThat(lease.get("termination").get("consents").size()).isEqualTo(1);
        call("POST",own(lease)+"/terminations/"+ending+"/accept",acceptBody(lease,true),inventory.tenant,403);
        lease=node(call("POST",inventory.path(lease)+"/terminations/"+ending+"/accept",acceptBody(lease,false),inventory.owner,200));
        assertThat(lease.get("termination").get("consents").size()).isEqualTo(2);
        assertThat(lease.get("status").asText()).isEqualTo("ACTIVE");
        lease=node(call("POST",inventory.path(lease)+"/complete",reason(lease,"Agreed move-out date reached."),inventory.owner,200));
        assertThat(lease.get("status").asText()).isEqualTo("TERMINATED");
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("UNAVAILABLE");
        call("POST",inventory.path(lease)+"/release-unit",Map.of("version",lease.get("version").asLong(),"vacancyConfirmed",false,"message","Unsafe release"),inventory.owner,400);
        call("POST",inventory.path(lease)+"/release-unit",Map.of("version",lease.get("version").asLong(),"vacancyConfirmed",true,"message","Vacancy checked."),inventory.owner,200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void signedLeaseCanEndByMutualAgreementBeforeMoveIn() throws Exception {
        Inventory inventory=inventory(); var future=new HashMap<>(terms()); future.put("startDate",today().plusDays(5).toString());
        JsonNode lease=send(inventory,draft(inventory,future)); lease=sign(inventory,lease,false); lease=sign(inventory,lease,true);
        lease=requestEnd(inventory,lease,false,today()); String ending=lease.get("termination").get("id").asText();
        lease=node(call("POST",own(lease)+"/terminations/"+ending+"/accept",acceptBody(lease,true),inventory.tenant,200));
        call("POST",inventory.path(lease)+"/complete",reason(lease,"Both parties agreed not to move in."),inventory.owner,200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE application_id=?",Integer.class,UUID.fromString(inventory.application))).isZero();
    }

    @Test
    void pendingTerminationCanBeRejectedOrCancelledOnlyByTheRightParty() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=requestEnd(inventory,active(inventory),true,today().plusDays(3));
        String first=lease.get("termination").get("id").asText();
        call("POST",own(lease)+"/terminations/"+first+"/reject",reason(lease,"Reject own request"),inventory.tenant,403);
        lease=node(call("POST",inventory.path(lease)+"/terminations/"+first+"/reject",reason(lease,"Please propose another date."),inventory.owner,200));
        lease=requestEnd(inventory,lease,false,today().plusDays(5)); String second=lease.get("termination").get("id").asText();
        call("POST",own(lease)+"/terminations/"+second+"/cancel",reason(lease,"Not requester"),inventory.tenant,403);
        lease=node(call("POST",inventory.path(lease)+"/terminations/"+second+"/cancel",reason(lease,"Withdrawing my proposal."),inventory.owner,200));
        assertThat(lease.get("termination").isNull()).isTrue();
        assertThat(node(call("GET",own(lease)+"/terminations",null,inventory.tenant,200)).size()).isEqualTo(2);
    }

    @Test
    void terminationAcceptanceRequiresExactDocumentAndCannotBeReplayed() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=requestEnd(inventory,active(inventory),true,today());
        String ending=lease.get("termination").get("id").asText();
        var bad=new HashMap<>(acceptBody(lease,false)); bad.put("evidence",evidence(lease,false,"0".repeat(64),PASSWORD,"Lease Account",true));
        call("POST",inventory.path(lease)+"/terminations/"+ending+"/accept",bad,inventory.owner,409);
        lease=node(call("POST",inventory.path(lease)+"/terminations/"+ending+"/accept",acceptBody(lease,false),inventory.owner,200));
        call("POST",inventory.path(lease)+"/terminations/"+ending+"/accept",acceptBody(lease,false),inventory.owner,409);
        assertThat(lease.get("termination").get("consents").size()).isEqualTo(2);
    }

    @Test
    void terminationDocumentAndConsentRecordsAreProtected() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=requestEnd(inventory,active(inventory),true,today()); UUID ending=id(lease.get("termination"));
        String path=own(lease)+"/terminations/"+ending+"/document";
        byte[] bytes=client.send(request("GET",path,null,inventory.tenant),HttpResponse.BodyHandlers.ofByteArray()).body();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(lease.get("termination").get("documentHash").asText());
        assertThatThrownBy(()->jdbc.update("UPDATE lease_terminations SET message='changed' WHERE id=?",ending)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM lease_termination_consents WHERE termination_id=?",ending)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void pastTerminationProposalCannotBeAccepted() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=requestEnd(inventory,active(inventory),true,today().plusDays(1));
        clock.days(2); login(inventory.owner); login(inventory.tenant);
        String ending=lease.get("termination").get("id").asText();
        call("POST",inventory.path(lease)+"/terminations/"+ending+"/accept",acceptBody(lease,false),inventory.owner,409);
        call("POST",own(lease)+"/terminations/"+ending+"/cancel",reason(lease,"Date passed."),inventory.tenant,200);
    }

    @Test
    void notificationsAndDashboardsPreserveApplicationCompatibility() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=sent(inventory); lease=sign(inventory,lease,true);
        JsonNode ownerInbox=node(call("GET","/api/v1/me/notifications",null,inventory.owner,200));
        assertThat(ownerInbox.size()).isGreaterThan(0);
        assertThat(ownerInbox.get(0).get("leaseUrl").asText()).contains("/organizations/"+inventory.organization+"/properties/"+inventory.property+"/leases/");
        JsonNode tenantInbox=node(call("GET","/api/v1/me/notifications",null,inventory.tenant,200));
        assertThat(tenantInbox.toString()).contains("SENT","APPROVED","applicationUrl","leaseUrl");
        assertThat(node(call("GET","/api/v1/me/leases/summary",null,inventory.tenant,200)).get("counts").get("SENT").asInt()).isEqualTo(1);
        assertThat(node(call("GET",inventory.base()+"/summary",null,inventory.owner,200)).get("total").asInt()).isEqualTo(1);
    }

    @Test
    void revisionFromAnotherLeaseCannotBeDownloadedThroughKnownLeaseId() throws Exception {
        Inventory first=inventory(); JsonNode lease=sent(first);
        Inventory second=inventory(); JsonNode foreign=sent(second);
        call("GET",own(lease)+"/revisions/"+revision(foreign)+"/document",null,first.tenant,404);
        call("GET",first.path(lease)+"/revisions/"+revision(foreign)+"/receipt",null,first.owner,404);
        call("POST",first.base(),Map.of("applicationId",second.application,"applicationVersion",3,"terms",terms()),first.owner,404);
    }

    @Test
    void signedLeaseThatNeverActivatedCanCloseAtNaturalExpiry() throws Exception {
        Inventory inventory=inventory(); JsonNode lease=executed(inventory);
        clock.days(31); login(inventory.owner);
        lease=node(call("POST",inventory.path(lease)+"/complete",reason(lease,"Term expired without move-in."),inventory.owner,200));
        assertThat(lease.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(lease.get("activatedAt").isNull()).isTrue();
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_unit_reservations WHERE application_id=?",Integer.class,UUID.fromString(inventory.application))).isZero();
        call("POST",inventory.path(lease)+"/release-unit",Map.of("version",lease.get("version").asLong(),"vacancyConfirmed",true,"message","No possession taken; unit vacant."),inventory.owner,200);
        assertThat(unit(inventory).get("availability").asText()).isEqualTo("AVAILABLE");
    }

    private JsonNode draft(Inventory inventory) throws Exception { return draft(inventory,terms()); }
    private JsonNode draft(Inventory inventory,Map<String,Object> terms) throws Exception {
        return node(call("POST",inventory.base(),Map.of("applicationId",inventory.application,"applicationVersion",3,"terms",terms),inventory.owner,201));
    }
    private JsonNode send(Inventory inventory,JsonNode lease) throws Exception { return node(call("POST",inventory.path(lease)+"/send",version(lease),inventory.owner,200)); }
    private JsonNode sent(Inventory inventory) throws Exception { return send(inventory,draft(inventory)); }
    private JsonNode executed(Inventory inventory) throws Exception { JsonNode lease=sent(inventory); lease=sign(inventory,lease,true); return sign(inventory,lease,false); }
    private JsonNode active(Inventory inventory) throws Exception {
        JsonNode lease=executed(inventory);
        return node(call("POST",inventory.path(lease)+"/activate",Map.of("version",lease.get("version").asLong(),"possessionConfirmed",true),inventory.owner,200));
    }
    private JsonNode sign(Inventory inventory,JsonNode lease,boolean tenant) throws Exception {
        return node(call("POST",(tenant?own(lease):inventory.path(lease))+"/sign",signBody(lease,tenant),tenant?inventory.tenant:inventory.owner,200));
    }
    private JsonNode requestEnd(Inventory inventory,JsonNode lease,boolean tenant,LocalDate date) throws Exception {
        String path=tenant?own(lease):inventory.path(lease);
        var body=new HashMap<String,Object>(); body.put("version",lease.get("version").asLong()); body.put("proposedEndDate",date.toString()); body.put("message","Please end by mutual agreement.");
        JsonNode preview=node(call("POST",path+"/terminations/preview",body,tenant?inventory.tenant:inventory.owner,200));
        body.put("evidence",evidence(lease,tenant,preview.get("documentHash").asText(),PASSWORD,tenant?"Tenant Person":"Lease Account",true));
        return node(call("POST",path+"/terminations",body,tenant?inventory.tenant:inventory.owner,201));
    }
    private Map<String,Object> acceptBody(JsonNode lease,boolean tenant) {
        return Map.of("version",lease.get("version").asLong(),"evidence",evidence(lease,tenant,lease.get("termination").get("documentHash").asText(),PASSWORD,tenant?"Tenant Person":"Lease Account",true));
    }
    private Map<String,Object> signBody(JsonNode lease,boolean tenant) {
        return Map.of("version",lease.get("version").asLong(),"revisionId",revision(lease),"evidence",evidence(lease,tenant,hash(lease),PASSWORD,tenant?"Tenant Person":"Lease Account",true));
    }
    private Map<String,Object> evidence(JsonNode lease,boolean tenant,String hash,String password,String name,boolean consent) {
        return Map.of("documentHash",hash,"typedName",name,"password",password,"consent",consent);
    }
    private Map<String,Object> version(JsonNode lease) { return Map.of("version",lease.get("version").asLong()); }
    private Map<String,Object> reason(JsonNode lease,String text) { return Map.of("version",lease.get("version").asLong(),"message",text); }
    private Map<String,Object> terms() {
        return Map.of("title","Residential agreement","startDate",today().toString(),"endDate",today().plusDays(30).toString(),"monthlyRent","25000","deposit","50000","currency","KES","rentDueDay",5,
                "agreementText","Negotiated agreement for caf\u00e9 premises.\nBoth parties must review this supplied agreement text.");
    }
    private String own(JsonNode lease) { return "/api/v1/me/leases/"+lease.get("id").asText(); }
    private String revision(JsonNode lease) { return lease.get("revision").get("id").asText(); }
    private String hash(JsonNode lease) { return lease.get("revision").get("documentHash").asText(); }
    private UUID id(JsonNode node) { return UUID.fromString(node.get("id").asText()); }
    private String constraint() { return "lease_test_"+UUID.randomUUID().toString().replace("-",""); }
    private JsonNode manager(Inventory inventory,JsonNode lease) throws Exception { return node(call("GET",inventory.path(lease),null,inventory.owner,200)); }
    private JsonNode tenant(Inventory inventory,JsonNode lease) throws Exception { return node(call("GET",own(lease),null,inventory.tenant,200)); }
    private JsonNode unit(Inventory inventory) throws Exception { return node(call("GET",inventory.unitPath(),null,inventory.owner,200)); }
    private JsonNode app(Inventory inventory) throws Exception { return node(call("GET",inventory.propertyPath()+"/applications/"+inventory.application,null,inventory.owner,200)); }
    private Map<String,Object> unitDetails() { return Map.of("label","A","bedrooms",2,"bathrooms",1,"monthlyRent","25000","deposit","50000","currency","KES","availability","AVAILABLE"); }
    private Inventory inventory() throws Exception {
        Account owner=account(); Account tenant=account(); String organization=organization(owner,"LANDLORD");
        call("POST","/api/v1/me/tenant-profile",Map.of("legalName","Tenant Person","phone","+254711111111","countryCode","KE","city","Nairobi","employmentStatus","EMPLOYED"),tenant,201);
        String base="/api/v1/organizations/"+organization+"/properties";
        String property=node(call("POST",base,Map.of("name","Lease property","type","HOUSE","countryCode","KE","city","Nairobi","address","Private lease premises"),owner,201)).get("id").asText();
        String propertyPath=base+"/"+property;
        call("PATCH",propertyPath+"/status",Map.of("version",0,"status","ACTIVE"),owner,200);
        String unit=node(call("POST",propertyPath+"/units",unitDetails(),owner,201)).get("id").asText();
        String listing=node(call("POST",propertyPath+"/listings",Map.of("unitId",unit,"details",Map.of("title","Lease rental","description","Public rental offer","showExactLocation",false)),owner,201)).get("id").asText();
        call("POST",propertyPath+"/listings/"+listing+"/publish",Map.of("version",0,"propertyVersion",1,"unitVersion",0),owner,200);
        String application=node(call("POST","/api/v1/me/applications",Map.of("listingId",listing,"listingVersion",1,"details",Map.of("householdSize",2,"requestedMoveInDate",today().plusDays(14).toString(),"message","Interested")),tenant,201)).get("id").asText();
        call("POST","/api/v1/me/applications/"+application+"/submit",Map.of("version",0,"profileVersion",0,"listingVersion",1,"consentToShare",true),tenant,200);
        call("POST",propertyPath+"/applications/"+application+"/review",Map.of("version",1),owner,200);
        call("POST",propertyPath+"/applications/"+application+"/approve",Map.of("version",2,"message","Approved for lease preparation."),owner,200);
        return new Inventory(owner,tenant,organization,property,unit,listing,application);
    }
    private Account account() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        UUID id=id(node(call("POST","/api/v1/auth/register",Map.of("email",email,"password",PASSWORD,"displayName","Lease Account"),null,201)));
        Account account=new Account(id,email); login(account); return account;
    }
    private void login(Account account) throws Exception { account.token=node(call("POST","/api/v1/auth/login",Map.of("email",account.email,"password",PASSWORD),null,200)).get("accessToken").asText(); }
    private String organization(Account actor,String type) throws Exception { return node(call("POST","/api/v1/organizations",Map.of("name","Lease business","type",type),actor,201)).get("id").asText(); }
    private LocalDate today() { return LocalDate.now(clock); }
    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpRequest request(String method,String path,Object body,Account actor) {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(actor!=null) request.header("Authorization","Bearer "+actor.token);
        if(body!=null) request.header("Content-Type","application/json");
        return request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private HttpResponse<String> call(String method,String path,Object body,Account actor,int expected) throws Exception {
        var response=client.send(request(method,path,body,actor),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s",method,path,response.body()).isEqualTo(expected); return response;
    }
    private static class Account {
        final UUID id; final String email; String token;
        Account(UUID id,String email) { this.id=id; this.email=email; }
    }
    private record Inventory(Account owner,Account tenant,String organization,String property,String unit,String listing,String application) {
        String propertyPath() { return "/api/v1/organizations/"+organization+"/properties/"+property; }
        String unitPath() { return propertyPath()+"/units/"+unit; }
        String base() { return propertyPath()+"/leases"; }
        String path(JsonNode lease) { return base()+"/"+lease.get("id").asText(); }
    }
    @TestConfiguration
    static class TestClockConfiguration {
        @Bean @Primary LeaseTestClock leaseClock() { return new LeaseTestClock(); }
    }
    static class LeaseTestClock extends Clock {
        volatile Instant now=Instant.now();
        void days(long days) { now=now.plus(Duration.ofDays(days)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
}
