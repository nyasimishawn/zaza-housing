package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"zaza.documents.uploads-per-hour=3", "zaza.communication.enquiries-per-hour=2"})
@ActiveProfiles("test")
class DocumentVerificationTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DocumentTestClock clock;
  private static final java.nio.file.Path IMAGE_ROOT =
      java.nio.file.Path.of(".work", "encrypted-document-tests-" + UUID.randomUUID())
          .toAbsolutePath();

  @org.springframework.test.context.DynamicPropertySource
  static void images(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("zaza.documents.directory", () -> IMAGE_ROOT.toString());
    registry.add(
        "zaza.documents.keys",
        () -> "test-v1:" + java.util.Base64.getEncoder().encodeToString(TEST_KEY));
    registry.add("zaza.documents.active-key-id", () -> "test-v1");
  }

  private static final byte[] TEST_KEY = new java.security.SecureRandom().generateSeed(32);

  @BeforeEach
  void resetClock() {
    clock.now = Instant.parse("2026-11-01T12:00:00Z");
  }

  @Test void multipagePdfsBecomeEncryptedStaticCopiesWithPrivatePagePreviews() throws Exception {
    var f=personal();var metadata=metadata("PROPERTY_OWNERSHIP",0);byte[] original=pdf(2,false);
    var response=uploadRaw(f,null,metadata,original);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var d=node(response);var version=d.get("currentVersion");
    assertThat(version.get("contentType").asText()).isEqualTo("application/pdf");assertThat(version.get("pageCount").asInt()).isEqualTo(2);
    byte[] canonical=download(content(f,d),f.actor,200).body();
    assertThat(hash(canonical)).isEqualTo(version.get("contentHash").asText());
    try(var stored=org.apache.pdfbox.Loader.loadPDF(canonical)){
      assertThat(stored.getNumberOfPages()).isEqualTo(2);assertThat(stored.getDocumentCatalog().getOpenAction()).isNull();
      assertThat(stored.getDocumentInformation().getAuthor()).isNull();
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(stored)).isBlank();
    }
    assertThat(java.nio.file.Files.readAllBytes(IMAGE_ROOT.resolve(storage(d)))).isNotEqualTo(canonical);
    String pages=content(f,d).replace("/content","/pages/");
    for(int page=1;page<=2;page++){
      var preview=node(call("GET",pages+page,null,f.actor,200));byte[] bytes=Base64.getDecoder().decode(preview.get("bytes").asText());
      assertThat(preview.get("versionId")).isEqualTo(version.get("id"));assertThat(preview.get("pageNumber").asInt()).isEqualTo(page);
      assertThat(preview.get("pageCount").asInt()).isEqualTo(2);assertThat(hash(bytes)).isEqualTo(preview.get("contentHash").asText());
      assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))).isNotNull();
    }
    call("GET",pages+3,null,f.actor,404);call("GET",pages+0,null,f.actor,404);call("GET",pages+1,null,null,401);call("GET",pages+1,null,account(),404);
    var replay=uploadRaw(f,null,metadata,original);assertThat(replay.statusCode()).isEqualTo(201);assertThat(node(replay).get("id")).isEqualTo(d.get("id"));
    d=archive(f,d);call("GET",pages+1,null,f.actor,409);
  }
  @Test void encryptedOverpageAndSpoofedPdfsLeaveNoDocumentOrPlaintextFiles() throws Exception {
    var f=personal();long before=fileCount();
    for(byte[] bytes:List.of(pdf(21,false),pdf(1,true),"%PDF-1.7 invalid".getBytes(StandardCharsets.UTF_8))){
      assertThat(uploadRaw(f,null,metadata("OTHER",0),bytes).statusCode()).isEqualTo(400);
    }
    assertThat(node(call("GET",base(f),null,f.actor,200))).isEmpty();assertThat(fileCount()).isEqualTo(before);
  }
  @Test void pdfVersionsDoNotChangePreviouslySharedPageBytesAndTamperingIsRejected() throws Exception {
    var f=personal();var response=uploadRaw(f,null,metadata("LEASE",0),pdf(2,false));assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var d=node(response);String old=content(f,d).replace("/content","/pages/2");
    var preview=node(call("GET",old,null,f.actor,200));d=replace(f,d,pdf(1,false));
    assertThat(node(call("GET",old,null,f.actor,200)).get("bytes")).isEqualTo(preview.get("bytes"));
    String key=storage(d);byte[] original=java.nio.file.Files.readAllBytes(IMAGE_ROOT.resolve(key));
    byte[] modified=original.clone();modified[modified.length-1]^=1;java.nio.file.Files.write(IMAGE_ROOT.resolve(key),modified);
    try{call("GET",content(f,d).replace("/content","/pages/1"),null,f.actor,503);}finally{java.nio.file.Files.write(IMAGE_ROOT.resolve(key),original);}
  }
  @Test void propertyFilesLinkExactOrganizationVersionsWithoutGrantingOtherWorkspacesAccess() throws Exception {
    var owner=account();String org=organization(owner,"LANDLORD");var f=new VaultOwner(owner,org);
    String root="/api/v1/organizations/"+org+"/properties";
    String property=node(call("POST",root,Map.of("name","PDF property","type","HOUSE","countryCode","KE","city","Nairobi","address","Private test"),owner,201)).get("id").asText();
    String path=root+"/"+property+"/documents";
    var response=uploadRaw(f,null,metadata("LEASE",0),pdf(2,false));assertThat(response.statusCode()).as(response.body()).isEqualTo(201);var d=node(response);
    UUID request=UUID.randomUUID();var body=Map.of("requestId",request,"propertyVersion",0,"documentId",d.get("id").asText(),"documentVersionId",d.get("currentVersion").get("id").asText());
    var link=node(call("POST",path,body,owner,201));assertThat(node(call("POST",path,body,owner,201)).get("id")).isEqualTo(link.get("id"));
    call("GET",path,null,account(),404);
    d=replace(f,d,pdf(1,false));var listed=node(call("GET",path,null,owner,200));
    assertThat(listed.get(0).get("linkedVersion").get("pageCount").asInt()).isEqualTo(2);
    assertThat(listed.get(0).get("document").get("currentVersion").get("pageCount").asInt()).isEqualTo(1);
    assertThat(node(call("GET",base(f)+"?q=Sensitive&kind=PDF&category=LEASE",null,owner,200)).size()).isEqualTo(1);
    assertThat(node(call("GET",base(f)+"?kind=IMAGE",null,owner,200))).isEmpty();
    var personal=personal(owner);var privateDoc=upload(personal,"IDENTITY");
    call("POST",path,Map.of("requestId",UUID.randomUUID(),"propertyVersion",0,"documentId",privateDoc.get("id").asText(),"documentVersionId",privateDoc.get("currentVersion").get("id").asText()),owner,404);
    call("DELETE",path+"/"+link.get("id").asText(),null,owner,204);
    assertThat(node(call("GET",path,null,owner,200))).isEmpty();
    download(content(f,d),owner,200);call("POST",path,body,owner,409);
  }

  private byte[] pdf(int pages,boolean encrypted)throws Exception{
    try(var d=new org.apache.pdfbox.pdmodel.PDDocument();var out=new java.io.ByteArrayOutputStream()){
      d.getDocumentInformation().setAuthor("PRIVATE_AUTHOR");
      d.getDocumentCatalog().setOpenAction(new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('PRIVATE_SCRIPT')"));
      for(int i=0;i<pages;i++){
        var page=new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(300,200));d.addPage(page);
        try(var c=new org.apache.pdfbox.pdmodel.PDPageContentStream(d,page)){
          c.beginText();c.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),18);c.newLineAtOffset(30,100);c.showText("Private property page "+(i+1));c.endText();
        }
      }
      if(encrypted)d.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret","reader-secret",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
      d.save(out);return out.toByteArray();
    }
  }

  @Test
  void scansAreEncryptedAtRestAndDownloadedAsPrivateAttachments() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var bytes = download(content(f, d), f.actor, 200);
    assertThat(bytes.headers().firstValue("cache-control").orElse("")).contains("no-store");
    assertThat(bytes.headers().firstValue("content-disposition").orElse(""))
        .contains("attachment")
        .doesNotContain("PASSPORT");
    assertThat(bytes.headers().firstValue("content-security-policy").orElse(""))
        .contains("sandbox");
    assertThat(hash(bytes.body())).isEqualTo(d.get("currentVersion").get("contentHash").asText());
    String key = storage(d);
    byte[] encrypted = java.nio.file.Files.readAllBytes(IMAGE_ROOT.resolve(key));
    assertThat(encrypted).isNotEqualTo(bytes.body());
    assertThat(encrypted.length).isGreaterThan(bytes.body().length);
    assertThat(d.toString())
        .doesNotContain(
            "storageKey",
            "keyId",
            "PASSPORT",
            "ownerUserId",
            java.util.Base64.getEncoder().encodeToString(TEST_KEY));
  }

  @Test
  void documentVersionsAreImmutableAndDoNotReplaceEarlierContent() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    String old = content(f, d);
    byte[] first = download(old, f.actor, 200).body();
    d = replace(f, d, png(0xFF1122));
    assertThat(d.get("currentVersion").get("number").asInt()).isEqualTo(2);
    assertThat(download(old, f.actor, 200).body()).isEqualTo(first);
    assertThat(download(content(f, d), f.actor, 200).body()).isNotEqualTo(first);
    assertThat(node(call("GET", path(f, d) + "/versions", null, f.actor, 200)).size()).isEqualTo(2);
  }

  @Test
  void documentsRequireAuthenticationAndOwnershipEvenForAdministrators() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var outsider = operator();
    call("GET", path(f, d), null, null, 401);
    call("GET", path(f, d), null, outsider, 404);
    download(content(f, d), outsider, 404);
    assertThat(node(call("GET", base(personal(outsider)), null, outsider, 200))).isEmpty();
  }

  @Test
  void organizationDocumentsRequireCurrentManagementMembership() throws Exception {
    var f = business();
    var d = upload(f, "BUSINESS_REGISTRATION");
    var member = account();
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'MEMBER')",
        UUID.fromString(f.org),
        member.id);
    call("GET", path(f, d), null, member, 403);
    jdbc.update(
        "UPDATE organization_memberships SET role='ADMIN' WHERE organization_id=? AND user_id=?",
        UUID.fromString(f.org),
        member.id);
    call("GET", path(f, d), null, member, 200);
    jdbc.update(
        "DELETE FROM organization_memberships WHERE organization_id=? AND user_id=?",
        UUID.fromString(f.org),
        member.id);
    download(content(f, d), member, 404);
  }

  @Test
  void uploadIdempotencyBindsProcessedContentAndMetadataWithoutConsumingQuotaTwice()
      throws Exception {
    var f = personal();
    var metadata = metadata("IDENTITY", 0);
    var a = uploadRaw(f, null, metadata, png(0));
    assertThat(a.statusCode()).isEqualTo(201);
    var d = node(a);
    var b = uploadRaw(f, null, metadata, png(0));
    assertThat(b.statusCode()).isEqualTo(201);
    assertThat(node(b).get("id")).isEqualTo(d.get("id"));
    assertThat(uploadRaw(f, null, metadata, png(1)).statusCode()).isEqualTo(409);
    upload(f, "IDENTITY");
    upload(f, "IDENTITY");
    assertThat(uploadRaw(f, null, metadata("IDENTITY", 0), png(0)).statusCode()).isEqualTo(429);
  }

  @Test
  void onlyDecodedImagesAreAcceptedAndPrivateMetadataIsDiscarded() throws Exception {
    var f = personal();
    long before = fileCount();
    assertThat(
            uploadRaw(
                    f,
                    null,
                    metadata("IDENTITY", 0),
                    "%PDF-1.7 active document".getBytes(StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            uploadRaw(
                    f,
                    null,
                    metadata("IDENTITY", 0),
                    "<script>bad</script>".getBytes(StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(400);
    var image = png(0);
    byte[] marked = java.util.Arrays.copyOf(image, image.length + 20);
    System.arraycopy(
        "PRIVATE-EXIF-MARKER".getBytes(StandardCharsets.UTF_8), 0, marked, image.length, 19);
    var d = node(uploadRaw(f, null, metadata("IDENTITY", 0), marked));
    assertThat(
            new String(download(content(f, d), f.actor, 200).body(), StandardCharsets.ISO_8859_1))
        .doesNotContain("PRIVATE-EXIF-MARKER");
    assertThat(fileCount()).isEqualTo(before + 1);
  }

  @Test
  void auditFailureRemovesUncommittedCiphertextAndRollsBackMetadata() throws Exception {
    var f = personal();
    long before = fileCount();
    String constraint = constraint();
    var m = metadata("IDENTITY", 0);
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'VAULT_DOCUMENT_UPLOADED') NOT VALID");
    try {
      assertThat(uploadRaw(f, null, m, png(0)).statusCode()).isEqualTo(409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(fileCount()).isEqualTo(before);
    assertThat(node(call("GET", base(f), null, f.actor, 200))).isEmpty();
    assertThat(uploadRaw(f, null, m, png(0)).statusCode()).isEqualTo(201);
  }

  @Test
  void authenticatedEncryptionRejectsTamperingWithoutExposingKeys() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var file = IMAGE_ROOT.resolve(storage(d));
    byte[] bytes = java.nio.file.Files.readAllBytes(file);
    bytes[bytes.length - 1] ^= 1;
    java.nio.file.Files.write(file, bytes);
    var response = download(content(f, d), f.actor, 503);
    assertThat(new String(response.body(), StandardCharsets.UTF_8))
        .doesNotContain(
            java.util.Base64.getEncoder().encodeToString(TEST_KEY), "storage_key", "AEADBadTag");
  }

  @Test
  void swappingCiphertextBetweenVersionsIsRejected() throws Exception {
    var f = personal();
    var a = upload(f, "IDENTITY");
    var b = upload(f, "IDENTITY");
    java.nio.file.Files.copy(
        IMAGE_ROOT.resolve(storage(a)),
        IMAGE_ROOT.resolve(storage(b)),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    download(content(f, b), f.actor, 503);
  }

  @Test
  void retentionOnlyExtendsAndPurgeRequiresArchivedExpiredEvidence() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var b = new HashMap<>(command(d));
    b.put("retainUntil", clock.instant().plus(Duration.ofDays(1)).toString());
    call("POST", path(f, d) + "/retention", b, f.actor, 400);
    b.put("retainUntil", clock.instant().plus(Duration.ofDays(400)).toString());
    d = node(call("POST", path(f, d) + "/retention", b, f.actor, 200));
    call("POST", path(f, d) + "/purge", purge(d), f.actor, 409);
    d = archive(f, d);
    call("POST", path(f, d) + "/purge", purge(d), f.actor, 409);
    assertThat(d.get("status").asText()).isEqualTo("ARCHIVED");
    download(content(f, d), f.actor, 409);
  }

  @Test
  void purgeIsExplicitTwoPhaseAndKeepsImmutableMetadata() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    String key = storage(d);
    d = archive(f, d);
    clock.days(366);
    login(f.actor);
    var b = purge(d);
    b.put("consent", false);
    call("POST", path(f, d) + "/purge", b, f.actor, 400);
    b = purge(d);
    b.put("password", "Incorrect-password-value");
    call("POST", path(f, d) + "/purge", b, f.actor, 401);
    var request = purge(d);
    d = node(call("POST", path(f, d) + "/purge", request, f.actor, 200));
    call("POST", path(f, d) + "/purge", request, f.actor, 200);
    assertThat(java.nio.file.Files.exists(IMAGE_ROOT.resolve(key))).isTrue();
    download(content(f, d), f.actor, 409);
    var process = command(d);
    d = node(call("POST", path(f, d) + "/purge/process", process, f.actor, 200));
    call("POST", path(f, d) + "/purge/process", process, f.actor, 200);
    assertThat(d.get("status").asText()).isEqualTo("PURGED");
    assertThat(java.nio.file.Files.exists(IMAGE_ROOT.resolve(key))).isFalse();
    assertThat(node(call("GET", path(f, d) + "/versions", null, f.actor, 200)).size()).isEqualTo(1);
  }

  @Test
  void interruptedPurgeCannotRestoreAccessAndCanRetryAbsentFiles() throws Exception {
    var f = personal();
    var d = archive(f, upload(f, "IDENTITY"));
    clock.days(366);
    login(f.actor);
    d = node(call("POST", path(f, d) + "/purge", purge(d), f.actor, 200));
    var process = command(d);
    String constraint = constraint();
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'VAULT_PURGE_PROCESSED') NOT VALID");
    try {
      call("POST", path(f, d) + "/purge/process", process, f.actor, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    download(content(f, d), f.actor, 409);
    call("POST", path(f, d) + "/restore", command(d), f.actor, 409);
    d = node(call("POST", path(f, d) + "/purge/process", process, f.actor, 200));
    assertThat(d.get("status").asText()).isEqualTo("PURGED");
  }

  @Test
  void competingVersionUploadsRejectStaleWrites() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var m = versionMetadata(d);
    var first =
        client.sendAsync(uploadRequest(f, d, m, png(1)), HttpResponse.BodyHandlers.ofString());
    var second =
        client.sendAsync(
            uploadRequest(f, d, versionMetadata(d), png(2)), HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                first.get(30, TimeUnit.SECONDS).statusCode(),
                second.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
  }

  @Test
  void applicationSharingRequiresConsentAndCurrentApplicationVersion() throws Exception {
    var i = inventory();
    var f = personal(i.tenant);
    var d = upload(f, "IDENTITY");
    var b = grantBody(i, d);
    b.put("consent", false);
    call("POST", ownDocuments(i), b, i.tenant, 400);
    b = grantBody(i, d);
    b.put("applicationVersion", 999);
    call("POST", ownDocuments(i), b, i.tenant, 409);
    b = grantBody(i, d);
    var g = node(call("POST", ownDocuments(i), b, i.tenant, 201));
    call("POST", ownDocuments(i), b, i.tenant, 201);
    download(reviewDocuments(i) + "/" + g.get("id").asText() + "/content", i.owner, 200);
    download(content(f, d), i.owner, 404);
  }

  @Test
  void sharedApplicationVersionsDoNotFollowLaterVaultUploads() throws Exception {
    var i = inventory();
    var f = personal(i.tenant);
    var d = upload(f, "IDENTITY");
    var g = grant(i, d);
    String shared = reviewDocuments(i) + "/" + g.get("id").asText() + "/content";
    byte[] before = download(shared, i.owner, 200).body();
    replace(f, d, png(0xFF1111));
    assertThat(download(shared, i.owner, 200).body()).isEqualTo(before);
    assertThat(
            node(call("GET", reviewDocuments(i), null, i.owner, 200))
                .get(0)
                .get("documentVersion")
                .get("id"))
        .isEqualTo(d.get("currentVersion").get("id"));
  }

  @Test
  void grantsCanBeRevokedAndNewConsentIsNeededToShareAgain() throws Exception {
    var i = inventory();
    var d = upload(personal(i.tenant), "IDENTITY");
    var g = grant(i, d);
    var b = command(g);
    call("POST", ownDocuments(i) + "/" + g.get("id").asText() + "/revoke", b, i.tenant, 200);
    call("POST", ownDocuments(i) + "/" + g.get("id").asText() + "/revoke", b, i.tenant, 200);
    download(reviewDocuments(i) + "/" + g.get("id").asText() + "/content", i.owner, 404);
    assertThat(node(call("GET", reviewDocuments(i), null, i.owner, 200))).isEmpty();
    var fresh = grant(i, d);
    assertThat(fresh.get("id")).isNotEqualTo(g.get("id"));
  }

  @Test
  void expiredSharingStopsAccessImmediately() throws Exception {
    var i = inventory();
    var d = upload(personal(i.tenant), "IDENTITY");
    var b = grantBody(i, d);
    b.put("expiresAt", clock.instant().plusSeconds(60).toString());
    var g = node(call("POST", ownDocuments(i), b, i.tenant, 201));
    clock.now = clock.now.plusSeconds(61);
    download(reviewDocuments(i) + "/" + g.get("id").asText() + "/content", i.owner, 404);
  }

  @Test
  void archiveAndRestoreNeverResurrectOldSharingConsent() throws Exception {
    var i = inventory();
    var f = personal(i.tenant);
    var d = upload(f, "IDENTITY");
    var g = grant(i, d);
    d = archive(f, d);
    d = node(call("POST", path(f, d) + "/restore", command(d), f.actor, 200));
    download(reviewDocuments(i) + "/" + g.get("id").asText() + "/content", i.owner, 404);
    grant(i, d);
  }

  @Test
  void applicationWithdrawalEndsScreeningDocumentAccess() throws Exception {
    var i = inventory();
    var d = upload(personal(i.tenant), "IDENTITY");
    var g = grant(i, d);
    var app = node(call("GET", "/api/v1/me/applications/" + i.application, null, i.tenant, 200));
    call(
        "POST",
        "/api/v1/me/applications/" + i.application + "/withdraw",
        Map.of("version", app.get("version").asLong()),
        i.tenant,
        200);
    download(reviewDocuments(i) + "/" + g.get("id").asText() + "/content", i.owner, 404);
  }

  @Test
  void sharingCannotAttachAnotherUsersDocument() throws Exception {
    var i = inventory();
    var f = personal();
    var d = upload(f, "IDENTITY");
    call("POST", ownDocuments(i), grantBody(i, d), i.tenant, 404);
  }

  @Test
  void formerAgencyImmediatelyLosesApplicationEvidenceAccess() throws Exception {
    var i = inventory();
    var d = upload(personal(i.tenant), "IDENTITY");
    var g = grant(i, d);
    var agent = account();
    String agency = organization(agent, "AGENCY");
    var p = node(call("GET", i.propertyPath(), null, i.owner, 200));
    call(
        "PATCH",
        i.propertyPath() + "/management",
        Map.of("version", p.get("version").asLong(), "managerOrganizationId", agency),
        i.owner,
        200);
    String content =
        reviewDocuments(i).replace(i.organization, agency)
            + "/"
            + g.get("id").asText()
            + "/content";
    download(content, agent, 200);
    p = node(call("GET", i.propertyPath(), null, i.owner, 200));
    var remove = new HashMap<String, Object>();
    remove.put("version", p.get("version").asLong());
    remove.put("managerOrganizationId", null);
    call("PATCH", i.propertyPath() + "/management", remove, i.owner, 200);
    download(content, agent, 404);
  }

  @Test
  void unsubmittedVerificationDraftIsInvisibleToOperators() throws Exception {
    var f = personal();
    var c = createCase(f, upload(f, "IDENTITY"));
    var op = operator();
    call("GET", admin(c), null, op, 404);
    assertThat(node(call("GET", "/api/v1/admin/verifications?limit=100", null, op, 200)).toString())
        .doesNotContain(c.get("id").asText(), "Private Verified Person");
  }

  @Test
  void submissionsRequireExplicitConsentPasswordAndExactRevision() throws Exception {
    var f = personal();
    var c = createCase(f, upload(f, "IDENTITY"));
    var b = submitBody(c);
    b.put("consent", false);
    call("POST", casePath(f, c) + "/submit", b, f.actor, 400);
    b = submitBody(c);
    b.put("password", "Incorrect-password-value");
    call("POST", casePath(f, c) + "/submit", b, f.actor, 401);
    b = submitBody(c);
    b.put("submissionId", UUID.randomUUID());
    call("POST", casePath(f, c) + "/submit", b, f.actor, 409);
    b = submitBody(c);
    c = node(call("POST", casePath(f, c) + "/submit", b, f.actor, 200));
    call("POST", casePath(f, c) + "/submit", b, f.actor, 200);
    assertThat(c.get("status").asText()).isEqualTo("SUBMITTED");
  }

  @Test
  void evidenceMustBelongToTheSubjectAndUseTheCorrectCategory() throws Exception {
    var f = personal();
    var foreign = upload(personal(), "IDENTITY");
    call(
        "POST",
        cases(f),
        Map.of("requestId", UUID.randomUUID(), "details", verificationDetails(f, foreign)),
        f.actor,
        404);
    var bad = upload(f, "OTHER");
    call(
        "POST",
        cases(f),
        Map.of("requestId", UUID.randomUUID(), "details", verificationDetails(f, bad)),
        f.actor,
        400);
  }

  @Test
  void oneActiveCasePerSubjectIsEnforced() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    createCase(f, d);
    call(
        "POST",
        cases(f),
        Map.of("requestId", UUID.randomUUID(), "details", verificationDetails(f, d)),
        f.actor,
        409);
  }

  @Test
  void selfVerificationAndCurrentOrganizationMembershipAreForbidden() throws Exception {
    var person = operator();
    var f = personal(person);
    var c = submit(f, createCase(f, upload(f, "IDENTITY")));
    call("POST", admin(c) + "/claim", review(c), person, 403);
    var org = business();
    var business = submit(org, createCase(org, upload(org, "BUSINESS_REGISTRATION")));
    var member = operator();
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'MEMBER')",
        UUID.fromString(org.org),
        member.id);
    call("POST", admin(business) + "/claim", review(business), member, 403);
  }

  @Test
  void unassignedAdministratorsCannotReadAnyPrivateVerificationEvidence() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = submit(f, createCase(f, d));
    var op = operator();
    call("GET", admin(c), null, op, 404);
    call("GET", admin(c) + "/evidence", null, op, 404);
    download(
        admin(c) + "/evidence/" + d.get("currentVersion").get("id").asText() + "/content", op, 404);
    var queue =
        node(call("GET", "/api/v1/admin/verifications?status=SUBMITTED&limit=100", null, op, 200));
    assertThat(queue.toString())
        .doesNotContain(
            "Private Verified Person", "documentVersionIds", "claimedName", "ownerUserId");
  }

  @Test
  void assignedReviewerCanOnlyReadTheSubmittedEvidenceBundle() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var unrelated = upload(f, "IDENTITY");
    var c = submit(f, createCase(f, d));
    var op = operator();
    claim(c, op);
    call("GET", admin(c), null, op, 200);
    download(
        admin(c) + "/evidence/" + d.get("currentVersion").get("id").asText() + "/content", op, 200);
    download(
        admin(c) + "/evidence/" + unrelated.get("currentVersion").get("id").asText() + "/content",
        op,
        404);
    download(content(f, d), op, 404);
  }

  @Test
  void releaseRevokesEvidenceAndAllowsAnotherIndependentReviewer() throws Exception {
    var f = personal();
    var c = submit(f, createCase(f, upload(f, "IDENTITY")));
    var one = operator();
    var two = operator();
    claim(c, one);
    c = current(f, c);
    var body = review(c);
    call("POST", admin(c) + "/release", body, one, 200);
    call("POST", admin(c) + "/release", body, one, 200);
    call("GET", admin(c), null, one, 404);
    c = current(f, c);
    claim(c, two);
    call("GET", admin(c), null, two, 200);
    call("GET", admin(c), null, one, 404);
  }

  @Test
  void reviewerWhoBecomesRelatedCanStillReleaseButCannotReadOrDecide() throws Exception {
    var f = business();
    var c = submit(f, createCase(f, upload(f, "BUSINESS_REGISTRATION")));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'MEMBER')",
        UUID.fromString(f.org),
        op.id);
    call("GET", admin(c), null, op, 403);
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 403);
    call("POST", admin(c) + "/release", review(c), op, 200);
  }

  @Test
  void formerSubmissionPreparerCannotBecomeTheIndependentReviewer() throws Exception {
    var f = business();
    var d = upload(f, "BUSINESS_REGISTRATION");
    var preparer = operator();
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'ADMIN')",
        UUID.fromString(f.org),
        preparer.id);
    var asPreparer = new VaultOwner(preparer, f.org);
    var c = submit(asPreparer, createCase(asPreparer, d));
    jdbc.update(
        "DELETE FROM organization_memberships WHERE organization_id=? AND user_id=?",
        UUID.fromString(f.org),
        preparer.id);
    call("POST", admin(c) + "/claim", review(c), preparer, 403);
  }

  @Test
  void withdrawalImmediatelyRevokesReviewerAccessAndVerificationHolds() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = submit(f, createCase(f, d));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    call("POST", casePath(f, c) + "/withdraw", command(c), f.actor, 200);
    call("GET", admin(c), null, op, 404);
    archive(f, d);
  }

  @Test
  void changesRequestedRequiresANewFrozenSubmissionBeforeReReview() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = submit(f, createCase(f, d));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    call("POST", admin(c) + "/decide", decision(c, "REQUEST_CHANGES"), op, 200);
    c = current(f, c);
    call("GET", admin(c), null, op, 404);
    call("POST", casePath(f, c) + "/submit", submitBody(c), f.actor, 409);
    var next = replace(f, d, png(9));
    c =
        node(
            call(
                "PUT",
                casePath(f, c),
                Map.of(
                    "requestId",
                    UUID.randomUUID(),
                    "version",
                    c.get("version").asLong(),
                    "details",
                    verificationDetails(f, next)),
                f.actor,
                200));
    c = submit(f, c);
    claim(c, op);
    download(
        admin(c) + "/evidence/" + d.get("currentVersion").get("id").asText() + "/content", op, 404);
    download(
        admin(c) + "/evidence/" + next.get("currentVersion").get("id").asText() + "/content",
        op,
        200);
  }

  @Test
  void manualApprovalRequiresCompletedChecksBoundedValidityAndRetainedEvidence() throws Exception {
    var f = personal();
    var c = submit(f, createCase(f, upload(f, "IDENTITY")));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    var b = decision(c, "VERIFY");
    b.put("checksConfirmed", false);
    call("POST", admin(c) + "/decide", b, op, 400);
    b = decision(c, "VERIFY");
    b.put("validUntil", clock.instant().plus(Duration.ofDays(366)).toString());
    call("POST", admin(c) + "/decide", b, op, 400);
    b = decision(c, "VERIFY");
    b.put("validUntil", clock.instant().toString());
    call("POST", admin(c) + "/decide", b, op, 400);
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 200);
    call("GET", admin(c), null, op, 404);
    assertThat(node(call("GET", cases(f) + "/status", null, f.actor, 200)).get("method").asText())
        .isEqualTo("MANUAL_DOCUMENT_REVIEW");
  }

  @Test
  void validVerificationPreventsEvidenceArchiveUntilWithdrawn() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = verified(f, d);
    call("POST", path(f, d) + "/archive", command(d), f.actor, 409);
    call("POST", casePath(f, c) + "/withdraw", command(c), f.actor, 200);
    archive(f, d);
    assertThat(node(call("GET", cases(f) + "/status", null, f.actor, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
  }

  @Test
  void verificationExpiresImmediatelyAndAllowsAFreshCase() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = verified(f, d);
    clock.days(3);
    login(f.actor);
    assertThat(current(f, c).get("status").asText()).isEqualTo("EXPIRED");
    assertThat(node(call("GET", cases(f) + "/status", null, f.actor, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    createCase(f, d);
  }

  @Test
  void rejectionAndRevocationAreTerminalAndDoNotGrantEvidenceAccess() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = submit(f, createCase(f, d));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    call("POST", admin(c) + "/decide", decision(c, "REJECT"), op, 200);
    call("GET", admin(c), null, op, 404);
    c = verified(f, d);
    var revoke = review(c);
    call("POST", admin(c) + "/revoke", revoke, op, 200);
    call("POST", admin(c) + "/revoke", revoke, op, 200);
    assertThat(current(f, c).get("status").asText()).isEqualTo("REVOKED");
    call("GET", admin(c), null, op, 404);
  }

  @Test
  void competingReviewerClaimsYieldOneAssignment() throws Exception {
    var f = personal();
    var c = submit(f, createCase(f, upload(f, "IDENTITY")));
    var a = operator();
    var b = operator();
    var one =
        client.sendAsync(
            request("POST", admin(c) + "/claim", review(c), a),
            HttpResponse.BodyHandlers.ofString());
    var two =
        client.sendAsync(
            request("POST", admin(c) + "/claim", review(c), b),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                one.get(30, TimeUnit.SECONDS).statusCode(),
                two.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
  }

  @Test
  void decisionAuditFailureRollsBackVerificationAndCanRetryExactRequest() throws Exception {
    var f = personal();
    var c = submit(f, createCase(f, upload(f, "IDENTITY")));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    var b = decision(c, "VERIFY");
    String constraint = constraint();
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'VAULT_VERIFICATION_DECIDED') NOT VALID");
    try {
      call("POST", admin(c) + "/decide", b, op, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(current(f, c).get("status").asText()).isEqualTo("UNDER_REVIEW");
    assertThat(node(call("GET", cases(f) + "/status", null, f.actor, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    call("POST", admin(c) + "/decide", b, op, 200);
  }

  @Test
  void businessNameMustMatchAndRenamingInvalidatesCurrentVerification() throws Exception {
    var f = business();
    var d = upload(f, "BUSINESS_REGISTRATION");
    var details = new HashMap<>(verificationDetails(f, d));
    details.put("claimedName", "Another company");
    call(
        "POST", cases(f), Map.of("requestId", UUID.randomUUID(), "details", details), f.actor, 400);
    var c = verified(f, d);
    assertThat(node(call("GET", cases(f) + "/status", null, f.actor, 200)).get("status").asText())
        .isEqualTo("VERIFIED");
    call(
        "PATCH",
        "/api/v1/organizations/" + f.org,
        Map.of("name", "Renamed business"),
        f.actor,
        200);
    assertThat(node(call("GET", cases(f) + "/status", null, f.actor, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    var fresh = createCase(f, d);
    assertThat(current(f, c).get("status").asText()).isEqualTo("REVOKED");
  }

  @Test
  void verificationUpdatesProviderBadgeWithoutLeakingSubjectDetails() throws Exception {
    var f = business();
    providerProfile(f);
    var d = upload(f, "BUSINESS_REGISTRATION");
    var c = verified(f, d);
    var profile =
        node(
            call("GET", "/api/v1/organizations/" + f.org + "/service-profile", null, f.actor, 200));
    assertThat(profile.get("verificationStatus").asText()).isEqualTo("VERIFIED");
    assertThat(profile.get("verificationMethod").asText()).isEqualTo("MANUAL_DOCUMENT_REVIEW");
    assertThat(profile.get("verificationValidUntil").asText())
        .isEqualTo(c.get("validUntil").asText());
    assertThat(profile.toString()).doesNotContain("documentVersionIds", "statement", "storageKey");
    clock.days(3);
    login(f.actor);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/organizations/" + f.org + "/service-profile",
                    null,
                    f.actor,
                    200))
                .get("verificationStatus")
                .asText())
        .isEqualTo("NOT_VERIFIED");
  }

  @Test
  void verificationEvidenceHoldsAndImmutableRecordsCannotBeBypassedByCrud() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    var c = submit(f, createCase(f, d));
    call("POST", path(f, d) + "/archive", command(d), f.actor, 409);
    UUID document = UUID.fromString(d.get("id").asText());
    UUID verification = UUID.fromString(c.get("id").asText());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE vault_document_versions SET content_hash=repeat('0',64) WHERE"
                        + " document_id=?",
                    document))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM verification_submission_shares WHERE case_id=?", verification))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.execute("TRUNCATE vault_events"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE verification_cases SET owner_user_id=? WHERE id=?",
                    account().id,
                    verification))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void notificationPreviewsAndOperationsNeverContainNamesOrPasswords() throws Exception {
    var f = business();
    var member = account();
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'ADMIN')",
        UUID.fromString(f.org),
        member.id);
    var d = upload(f, "BUSINESS_REGISTRATION");
    var c = submit(f, createCase(f, d));
    var op = operator();
    claim(c, op);
    var notices = node(call("GET", "/api/v1/me/notifications", null, member, 200));
    assertThat(notices.toString())
        .doesNotContain("Private Verified Person", PASSWORD, "BUSINESS_REGISTRATION", "storageKey");
    assertThat(
            jdbc.queryForList("SELECT * FROM vault_operations WHERE scope_key=?", "ORG:" + f.org)
                .toString())
        .doesNotContain(PASSWORD, "password");
  }

  @Test
  void privateMetadataHistoryAndQueuePaginationAreBounded() throws Exception {
    var f = personal();
    var d = upload(f, "IDENTITY");
    call("GET", base(f) + "?limit=101", null, f.actor, 400);
    call("GET", path(f, d) + "/history?offset=-1", null, f.actor, 400);
    call("GET", "/api/v1/admin/verifications?limit=0", null, operator(), 400);
    assertThat(
            call("GET", path(f, d), null, f.actor, 200)
                .headers()
                .firstValue("cache-control")
                .orElse(""))
        .contains("no-store");
  }

  @Test
  void ownershipReviewIsPropertyBoundPrivateAndExpiresWithoutRepublishing() throws Exception {
    var f = ownership();
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var c = createOwnership(f, d);
    assertThat(c.get("kind").asText()).isEqualTo("PROPERTY_OWNERSHIP");
    assertThat(c.get("property").get("propertyId").asText()).isEqualTo(f.property);
    assertThat(
            node(call("GET", ownershipStatus(f), null, f.owner.actor, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    var op = operator();
    call("GET", admin(c), null, op, 404);
    c = submit(f.owner, c);
    claim(c, op);
    c = current(f.owner, c);
    var review = node(call("GET", admin(c), null, op, 200));
    assertThat(review.get("property").get("address").asText())
        .isEqualTo("Private ownership premises");
    call("GET", casePath(f.owner, c), null, op, 404);
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 200);
    var badge = call("GET", publicOwnership(f), null, null, 200);
    assertThat(badge.body())
        .contains("VERIFIED", "MANUAL_DOCUMENT_REVIEW")
        .doesNotContain(
            f.property,
            "claimedName",
            "propertyName",
            "Private ownership premises",
            "documentVersionIds");
    assertThat(badge.headers().firstValue("cache-control").orElse("")).contains("no-store");
    assertThat(
            node(call("GET", cases(f.owner) + "/status", null, f.owner.actor, 200))
                .get("status")
                .asText())
        .isEqualTo("NOT_VERIFIED");
    clock.now = clock.now.plus(Duration.ofDays(3));
    login(f.owner.actor);
    assertThat(
            node(call("GET", ownershipStatus(f), null, f.owner.actor, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    assertThat(node(call("GET", publicOwnership(f), null, null, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    assertThat(current(f.owner, c).get("status").asText()).isEqualTo("EXPIRED");
    assertThat(createOwnership(f, d).get("id")).isNotEqualTo(c.get("id"));
  }

  @Test
  void ownershipRevocationRemovesBadgeImmediatelyAndRetainsHistory() throws Exception {
    var f = ownership();
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var c = submit(f.owner, createOwnership(f, d));
    var op = operator();
    claim(c, op);
    c = current(f.owner, c);
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 200);
    c = current(f.owner, c);
    var revoke = review(c);
    call("POST", admin(c) + "/revoke", revoke, op, 200);
    call("POST", admin(c) + "/revoke", revoke, op, 200);
    assertThat(node(call("GET", publicOwnership(f), null, null, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    assertThat(current(f.owner, c).get("status").asText()).isEqualTo("REVOKED");
    assertThat(
            node(call("GET", casePath(f.owner, c) + "/history", null, f.owner.actor, 200)).size())
        .isGreaterThan(3);
  }

  @Test
  void propertyClaimsCoexistWithBusinessAndOtherPropertiesButNeverDuplicateAnActiveClaim()
      throws Exception {
    var f = ownership();
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var body =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(f.owner, d));
    var c = node(call("POST", cases(f.owner), body, f.owner.actor, 201));
    assertThat(node(call("POST", cases(f.owner), body, f.owner.actor, 201)).get("id"))
        .isEqualTo(c.get("id"));
    call(
        "POST",
        cases(f.owner),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(f.owner, d)),
        f.owner.actor,
        409);
    var registration = upload(f.owner, "BUSINESS_REGISTRATION");
    createCase(f.owner, registration);
    String another = createOwnershipProperty(f.owner);
    call(
        "POST",
        cases(f.owner),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            another,
            "details",
            verificationDetails(f.owner, d)),
        f.owner.actor,
        201);
    var rows =
        node(
            call(
                "GET",
                cases(f.owner) + "?propertyId=" + f.property + "&limit=20",
                null,
                f.owner.actor,
                200));
    assertThat(rows.size()).isEqualTo(1);
    assertThat(rows.get(0).get("id")).isEqualTo(c.get("id"));
    call(
        "GET", cases(f.owner) + "?propertyId=" + f.property + "&limit=0", null, f.owner.actor, 400);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE verification_cases SET property_id=? WHERE id=?",
                    UUID.fromString(another),
                    id(c)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void ownershipEvidenceRequiresOwningOrganizationCorrectCategoryAndCountry() throws Exception {
    var f = ownership();
    var wrong = upload(f.owner, "BUSINESS_REGISTRATION");
    call(
        "POST",
        cases(f.owner),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(f.owner, wrong)),
        f.owner.actor,
        400);
    var other = business();
    var foreign = upload(other, "PROPERTY_OWNERSHIP");
    call(
        "POST",
        cases(f.owner),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(f.owner, foreign)),
        f.owner.actor,
        404);
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var details = new HashMap<String, Object>(verificationDetails(f.owner, d));
    details.put("countryCode", "UG");
    call(
        "POST",
        cases(f.owner),
        Map.of("requestId", UUID.randomUUID(), "propertyId", f.property, "details", details),
        f.owner.actor,
        400);
    call(
        "POST",
        cases(other),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(other, foreign)),
        other.actor,
        404);
    var personal = personal();
    var personalScan = upload(personal, "PROPERTY_OWNERSHIP");
    call(
        "POST",
        cases(personal),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(personal, personalScan)),
        personal.actor,
        400);
  }

  @Test
  void currentMembersAndDelegatedAgencyCannotIndependentlyApproveOwnership() throws Exception {
    var f = ownership();
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var c = submit(f.owner, createOwnership(f, d));
    jdbc.update("UPDATE app_users SET platform_role='PLATFORM_ADMIN' WHERE id=?", f.owner.actor.id);
    login(f.owner.actor);
    call("POST", admin(c) + "/claim", review(c), f.owner.actor, 403);
    var agency = operator();
    String org = organization(agency, "AGENCY");
    call(
        "PATCH",
        f.propertyPath() + "/management",
        Map.of("version", 1, "managerOrganizationId", org),
        f.owner.actor,
        200);
    call("POST", admin(c) + "/claim", review(c), agency, 403);
    call(
        "GET",
        "/api/v1/organizations/" + org + "/verifications?propertyId=" + f.property,
        null,
        agency,
        404);
    call(
        "GET",
        "/api/v1/organizations/" + org + "/properties/" + f.property + "/ownership-verification",
        null,
        agency,
        200);
    var independent = operator();
    claim(c, independent);
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'MEMBER')",
        UUID.fromString(f.owner.org),
        independent.id);
    call("GET", admin(c), null, independent, 403);
    call("POST", admin(c) + "/release", review(current(f.owner, c)), independent, 200);
  }

  @Test
  void changedPropertyIdentityPreventsApprovalAndInvalidatesExistingBadge() throws Exception {
    var f = ownership();
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var c = submit(f.owner, createOwnership(f, d));
    var op = operator();
    claim(c, op);
    c = current(f.owner, c);
    jdbc.update(
        "UPDATE properties SET address='Changed premises' WHERE id=?", UUID.fromString(f.property));
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 409);
    call("POST", admin(c) + "/decide", decision(c, "REQUEST_CHANGES"), op, 200);
    c = current(f.owner, c);
    call("POST", casePath(f.owner, c) + "/submit", submitBody(c), f.owner.actor, 409);
    call("POST", casePath(f.owner, c) + "/withdraw", command(c), f.owner.actor, 200);
    c = submit(f.owner, createOwnership(f, d));
    claim(c, op);
    c = current(f.owner, c);
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 200);
    assertThat(node(call("GET", publicOwnership(f), null, null, 200)).get("status").asText())
        .isEqualTo("VERIFIED");
    jdbc.update(
        "UPDATE properties SET name='Changed property name' WHERE id=?",
        UUID.fromString(f.property));
    assertThat(node(call("GET", publicOwnership(f), null, null, 200)).get("status").asText())
        .isEqualTo("NOT_VERIFIED");
    assertThat(current(f.owner, c).get("status").asText()).isEqualTo("REVOKED");
    createOwnership(f, d);
  }

  @Test
  void ownershipRetryRechecksMembershipAndPublicBadgeRespectsListingVisibility() throws Exception {
    var f = ownership();
    var d = upload(f.owner, "PROPERTY_OWNERSHIP");
    var member = account();
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'ADMIN')",
        UUID.fromString(f.owner.org),
        member.id);
    var body =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            f.property,
            "details",
            verificationDetails(f.owner, d));
    call("POST", cases(f.owner), body, member, 201);
    jdbc.update(
        "DELETE FROM organization_memberships WHERE organization_id=? AND user_id=?",
        UUID.fromString(f.owner.org),
        member.id);
    call("POST", cases(f.owner), body, member, 404);
    call("GET", publicOwnership(f), null, null, 200);
    call(
        "POST",
        f.propertyPath() + "/listings/" + f.listing + "/withdraw",
        Map.of("version", 1),
        f.owner.actor,
        200);
    call("GET", publicOwnership(f), null, null, 404);
  }

  private record Ownership(VaultOwner owner, String property, String listing) {
    String propertyPath() {
      return "/api/v1/organizations/" + owner.org + "/properties/" + property;
    }
  }

  private String createOwnershipProperty(VaultOwner f) throws Exception {
    String base = "/api/v1/organizations/" + f.org + "/properties";
    var p =
        node(
            call(
                "POST",
                base,
                Map.of(
                    "name",
                    "Ownership property",
                    "type",
                    "HOUSE",
                    "countryCode",
                    "KE",
                    "city",
                    "Nairobi",
                    "address",
                    "Private ownership premises"),
                f.actor,
                201));
    call(
        "PATCH",
        base + "/" + p.get("id").asText() + "/status",
        Map.of("version", 0, "status", "ACTIVE"),
        f.actor,
        200);
    return p.get("id").asText();
  }

  private Ownership ownership() throws Exception {
    var actor = account();
    var f = new VaultOwner(actor, organization(actor, "LANDLORD"));
    var p = createOwnershipProperty(f);
    String path = "/api/v1/organizations/" + f.org + "/properties/" + p;
    var unit = node(call("POST", path + "/units", unitDetails(), actor, 201));
    var listing =
        node(
            call(
                "POST",
                path + "/listings",
                Map.of(
                    "unitId",
                    unit.get("id").asText(),
                    "details",
                    Map.of(
                        "title",
                        "Ownership rental",
                        "description",
                        "A current public rental offer.",
                        "showExactLocation",
                        false)),
                actor,
                201));
    call(
        "POST",
        path + "/listings/" + listing.get("id").asText() + "/publish",
        Map.of("version", 0, "propertyVersion", 1, "unitVersion", 0),
        actor,
        200);
    return new Ownership(f, p, listing.get("id").asText());
  }

  private JsonNode createOwnership(Ownership f, JsonNode d) throws Exception {
    return node(
        call(
            "POST",
            cases(f.owner),
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "propertyId",
                f.property,
                "details",
                verificationDetails(f.owner, d)),
            f.owner.actor,
            201));
  }

  private String ownershipStatus(Ownership f) {
    return f.propertyPath() + "/ownership-verification";
  }

  private String publicOwnership(Ownership f) {
    return "/api/v1/listings/" + f.listing + "/ownership-verification";
  }

  private record VaultOwner(Account actor, String org) {}

  private VaultOwner personal() throws Exception {
    return personal(account());
  }

  private VaultOwner personal(Account a) {
    return new VaultOwner(a, null);
  }

  private VaultOwner business() throws Exception {
    var a = account();
    return new VaultOwner(a, organization(a, "SERVICE_PROVIDER"));
  }

  private String base(VaultOwner f) {
    return f.org == null ? "/api/v1/me/documents" : "/api/v1/organizations/" + f.org + "/documents";
  }

  private String path(VaultOwner f, JsonNode d) {
    return base(f) + "/" + d.get("id").asText();
  }

  private String content(VaultOwner f, JsonNode d) {
    return path(f, d) + "/versions/" + d.get("currentVersion").get("id").asText() + "/content";
  }

  private HashMap<String, Object> metadata(String category, long version) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            version,
            "category",
            category,
            "title",
            "Sensitive scan",
            "retainUntil",
            clock.instant().plus(Duration.ofDays(365)).toString()));
  }

  private HashMap<String, Object> versionMetadata(JsonNode d) {
    var m = metadata(d.get("category").asText(), d.get("version").asLong());
    m.put("title", d.get("title").asText());
    m.put("retainUntil", d.get("retainUntil").asText());
    return m;
  }

  private JsonNode upload(VaultOwner f, String category) throws Exception {
    var r = uploadRaw(f, null, metadata(category, 0), png(0));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    return node(r);
  }

  private JsonNode replace(VaultOwner f, JsonNode d, byte[] bytes) throws Exception {
    var r = uploadRaw(f, d, versionMetadata(d), bytes);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return node(r);
  }

  private JsonNode archive(VaultOwner f, JsonNode d) throws Exception {
    return node(call("POST", path(f, d) + "/archive", command(d), f.actor, 200));
  }

  private HashMap<String, Object> command(JsonNode d) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            d.get("version").asLong(),
            "reason",
            "Requested private workflow change."));
  }

  private HashMap<String, Object> purge(JsonNode d) {
    var b = command(d);
    b.put("consent", true);
    b.put("password", PASSWORD);
    return b;
  }

  private HttpRequest uploadRequest(VaultOwner f, JsonNode d, Object metadata, byte[] image)
      throws Exception {
    String boundary = "vault" + UUID.randomUUID().toString().replace("-", "");
    var out = new java.io.ByteArrayOutputStream();
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"metadata\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + json.writeValueAsString(metadata)
                + "\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\";"
                + " filename=\"../../PASSPORT.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(image);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HttpRequest.newBuilder(
            URI.create(
                "http://127.0.0.1:" + port + (d == null ? base(f) : path(f, d) + "/versions")))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + f.actor.token)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
        .build();
  }

  private HttpResponse<String> uploadRaw(VaultOwner f, JsonNode d, Object metadata, byte[] image)
      throws Exception {
    return client.send(uploadRequest(f, d, metadata, image), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> download(String path, Account actor, int status) throws Exception {
    var result =
        client.send(request("GET", path, null, actor), HttpResponse.BodyHandlers.ofByteArray());
    assertThat(result.statusCode()).as(path).isEqualTo(status);
    return result;
  }

  private String storage(JsonNode d) {
    return jdbc.queryForObject(
        "SELECT storage_key FROM vault_document_versions WHERE id=?",
        String.class,
        UUID.fromString(d.get("currentVersion").get("id").asText()));
  }

  private long fileCount() throws Exception {
    try (var files = java.nio.file.Files.list(IMAGE_ROOT)) {
      return files.count();
    }
  }

  private String hash(byte[] bytes) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private byte[] png(int color) throws Exception {
    var image = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 40; x++) for (int y = 0; y < 30; y++) image.setRGB(x, y, color);
    try (var out = new java.io.ByteArrayOutputStream()) {
      javax.imageio.ImageIO.write(image, "png", out);
      image.flush();
      return out.toByteArray();
    }
  }

  private String ownDocuments(Inventory i) {
    return "/api/v1/me/applications/" + i.application + "/documents";
  }

  private String reviewDocuments(Inventory i) {
    return i.propertyPath() + "/applications/" + i.application + "/documents";
  }

  private HashMap<String, Object> grantBody(Inventory i, JsonNode d) throws Exception {
    var app = node(call("GET", "/api/v1/me/applications/" + i.application, null, i.tenant, 200));
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "applicationVersion",
            app.get("version").asLong(),
            "documentId",
            d.get("id").asText(),
            "documentVersionId",
            d.get("currentVersion").get("id").asText(),
            "expiresAt",
            clock.instant().plus(Duration.ofDays(10)).toString(),
            "consent",
            true));
  }

  private JsonNode grant(Inventory i, JsonNode d) throws Exception {
    return node(call("POST", ownDocuments(i), grantBody(i, d), i.tenant, 201));
  }

  private String cases(VaultOwner f) {
    return f.org == null
        ? "/api/v1/me/verifications"
        : "/api/v1/organizations/" + f.org + "/verifications";
  }

  private String casePath(VaultOwner f, JsonNode c) {
    return cases(f) + "/" + c.get("id").asText();
  }

  private String admin(JsonNode c) {
    return "/api/v1/admin/verifications/" + c.get("id").asText();
  }

  private Map<String, Object> verificationDetails(VaultOwner f, JsonNode d) throws Exception {
    String name =
        f.org == null
            ? "Private Verified Person"
            : node(call("GET", "/api/v1/organizations/" + f.org, null, f.actor, 200))
                .get("name")
                .asText();
    return Map.of(
        "claimedName",
        name,
        "countryCode",
        "KE",
        "statement",
        "I authorize review of the exact scan versions and confirm the declared subject"
            + " information.",
        "documentVersionIds",
        List.of(d.get("currentVersion").get("id").asText()));
  }

  private JsonNode createCase(VaultOwner f, JsonNode d) throws Exception {
    return node(
        call(
            "POST",
            cases(f),
            Map.of("requestId", UUID.randomUUID(), "details", verificationDetails(f, d)),
            f.actor,
            201));
  }

  private HashMap<String, Object> submitBody(JsonNode c) {
    var b = command(c);
    b.remove("reason");
    b.put("submissionId", c.get("submission").get("id").asText());
    b.put("consent", true);
    b.put("password", PASSWORD);
    return b;
  }

  private JsonNode submit(VaultOwner f, JsonNode c) throws Exception {
    return node(call("POST", casePath(f, c) + "/submit", submitBody(c), f.actor, 200));
  }

  private JsonNode current(VaultOwner f, JsonNode c) throws Exception {
    return node(call("GET", casePath(f, c), null, f.actor, 200));
  }

  private Account operator() throws Exception {
    var a = account();
    jdbc.update("UPDATE app_users SET platform_role='PLATFORM_ADMIN' WHERE id=?", a.id);
    login(a);
    return a;
  }

  private HashMap<String, Object> review(JsonNode c) {
    var b = command(c);
    b.put("password", PASSWORD);
    return b;
  }

  private void claim(JsonNode c, Account op) throws Exception {
    call("POST", admin(c) + "/claim", review(c), op, 200);
  }

  private HashMap<String, Object> decision(JsonNode c, String decision) {
    var b = review(c);
    b.put("decision", decision);
    b.put("checksConfirmed", true);
    if (decision.equals("VERIFY"))
      b.put("validUntil", clock.instant().plus(Duration.ofDays(2)).toString());
    return b;
  }

  private JsonNode verified(VaultOwner f, JsonNode d) throws Exception {
    var c = submit(f, createCase(f, d));
    var op = operator();
    claim(c, op);
    c = current(f, c);
    call("POST", admin(c) + "/decide", decision(c, "VERIFY"), op, 200);
    return current(f, c);
  }

  private UUID id(JsonNode n) {
    return UUID.fromString(n.get("id").asText());
  }

  private String constraint() {
    return "test_" + UUID.randomUUID().toString().replace("-", "");
  }

  private void providerProfile(VaultOwner f) throws Exception {
    call(
        "POST",
        "/api/v1/organizations/" + f.org + "/service-profile",
        Map.of(
            "displayName",
            "Private Verified Business",
            "description",
            "Experienced independent service business with qualified staff.",
            "areas",
            List.of(Map.of("countryCode", "KE", "city", "Nairobi"))),
        f.actor,
        200);
  }

  private Map<String, Object> unitDetails() {
    return Map.of(
        "label",
        "A",
        "bedrooms",
        2,
        "bathrooms",
        1,
        "monthlyRent",
        "25000",
        "deposit",
        "50000",
        "currency",
        "KES",
        "availability",
        "AVAILABLE");
  }

  private Inventory inventory() throws Exception {
    Account owner = account();
    Account tenant = account();
    String organization = organization(owner, "LANDLORD");
    call(
        "POST",
        "/api/v1/me/tenant-profile",
        Map.of(
            "legalName",
            "Tenant Person",
            "phone",
            "+254711111111",
            "countryCode",
            "KE",
            "city",
            "Nairobi",
            "employmentStatus",
            "EMPLOYED"),
        tenant,
        201);
    String base = "/api/v1/organizations/" + organization + "/properties";
    String property =
        node(call(
                "POST",
                base,
                Map.of(
                    "name",
                    "Lease property",
                    "type",
                    "HOUSE",
                    "countryCode",
                    "KE",
                    "city",
                    "Nairobi",
                    "address",
                    "Private lease premises"),
                owner,
                201))
            .get("id")
            .asText();
    String propertyPath = base + "/" + property;
    call("PATCH", propertyPath + "/status", Map.of("version", 0, "status", "ACTIVE"), owner, 200);
    String unit =
        node(call("POST", propertyPath + "/units", unitDetails(), owner, 201)).get("id").asText();
    String listing =
        node(call(
                "POST",
                propertyPath + "/listings",
                Map.of(
                    "unitId",
                    unit,
                    "details",
                    Map.of(
                        "title",
                        "Lease rental",
                        "description",
                        "Public rental offer",
                        "showExactLocation",
                        false)),
                owner,
                201))
            .get("id")
            .asText();
    call(
        "POST",
        propertyPath + "/listings/" + listing + "/publish",
        Map.of("version", 0, "propertyVersion", 1, "unitVersion", 0),
        owner,
        200);
    String application =
        node(call(
                "POST",
                "/api/v1/me/applications",
                Map.of(
                    "listingId",
                    listing,
                    "listingVersion",
                    1,
                    "details",
                    Map.of(
                        "householdSize",
                        2,
                        "requestedMoveInDate",
                        today().plusDays(14).toString(),
                        "message",
                        "Interested")),
                tenant,
                201))
            .get("id")
            .asText();
    call(
        "POST",
        "/api/v1/me/applications/" + application + "/submit",
        Map.of("version", 0, "profileVersion", 0, "listingVersion", 1, "consentToShare", true),
        tenant,
        200);
    call(
        "POST",
        propertyPath + "/applications/" + application + "/review",
        Map.of("version", 1),
        owner,
        200);
    call(
        "POST",
        propertyPath + "/applications/" + application + "/approve",
        Map.of("version", 2, "message", "Approved for lease preparation."),
        owner,
        200);
    return new Inventory(owner, tenant, organization, property, unit, listing, application);
  }

  private Account account() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    UUID id =
        id(
            node(
                call(
                    "POST",
                    "/api/v1/auth/register",
                    Map.of("email", email, "password", PASSWORD, "displayName", "Lease Account"),
                    null,
                    201)));
    Account account = new Account(id, email);
    login(account);
    return account;
  }

  private void login(Account account) throws Exception {
    account.token =
        node(call(
                "POST",
                "/api/v1/auth/login",
                Map.of("email", account.email, "password", PASSWORD),
                null,
                200))
            .get("accessToken")
            .asText();
  }

  private String organization(Account actor, String type) throws Exception {
    return node(call(
            "POST",
            "/api/v1/organizations",
            Map.of("name", "Lease business", "type", type),
            actor,
            201))
        .get("id")
        .asText();
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private JsonNode node(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private HttpRequest request(String method, String path, Object body, Account actor) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30));
    if (actor != null) request.header("Authorization", "Bearer " + actor.token);
    if (body != null) request.header("Content-Type", "application/json");
    return request
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  private HttpResponse<String> call(
      String method, String path, Object body, Account actor, int expected) throws Exception {
    var response =
        client.send(request(method, path, body, actor), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("%s %s: %s", method, path, response.body())
        .isEqualTo(expected);
    return response;
  }

  private static class Account {
    final UUID id;
    final String email;
    String token;

    Account(UUID id, String email) {
      this.id = id;
      this.email = email;
    }
  }

  private record Inventory(
      Account owner,
      Account tenant,
      String organization,
      String property,
      String unit,
      String listing,
      String application) {
    String propertyPath() {
      return "/api/v1/organizations/" + organization + "/properties/" + property;
    }

    String unitPath() {
      return propertyPath() + "/units/" + unit;
    }

    String base() {
      return propertyPath() + "/leases";
    }

    String path(JsonNode lease) {
      return base() + "/" + lease.get("id").asText();
    }
  }

  @TestConfiguration
  static class DocumentClockConfiguration {
    @Bean
    @Primary
    DocumentTestClock leaseClock() {
      return new DocumentTestClock();
    }
  }

  static class DocumentTestClock extends Clock {
    volatile Instant now = Instant.now();

    void days(long days) {
      now = now.plus(Duration.ofDays(days));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
