package com.zaza.housing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PropertyImageTests {
    private static final String PASSWORD = "Image-test-password-42";
    private static final Path IMAGE_ROOT = Path.of(".work", "image-tests-" + UUID.randomUUID()).toAbsolutePath();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void images(DynamicPropertyRegistry registry) { registry.add("zaza.images.directory", () -> IMAGE_ROOT.toString()); }

    @Test
    void pngUploadIsResizedAndOriginalMetadataIsRemoved() throws Exception {
        Inventory inventory = inventory();
        byte[] original = pngWithMetadata(3200, 1600);
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("PRIVATE_GPS_COMMENT");
        JsonNode image = uploaded(inventory, original, "../../escape.png", "text/html", "Living room");
        assertThat(image.get("width").asInt()).isEqualTo(1600);
        assertThat(image.get("height").asInt()).isEqualTo(800);
        assertThat(image.get("contentType").asText()).isEqualTo("image/png");
        assertThat(image.toString()).doesNotContain("storageKey", "escape", "PRIVATE_GPS_COMMENT", ".local-images");
        var content = binary(image.get("contentUrl").asText(), inventory.owner.token, 200);
        assertThat(new String(content.body(), StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_GPS_COMMENT");
        assertThat(ImageIO.read(new ByteArrayInputStream(content.body())).getWidth()).isEqualTo(1600);
        assertThat(content.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        assertThat(content.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(content.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        String key = jdbc.queryForObject("SELECT storage_key FROM property_images WHERE id = ?", String.class, UUID.fromString(image.get("id").asText()));
        assertThat(key).matches("[0-9a-f-]{36}\\.png");
        assertThat(content.body().length).isEqualTo(image.get("byteSize").asInt());
    }

    @Test
    void jpegContentIsRecognizedFromBytesAndRemainsPrivateUntilSelected() throws Exception {
        Inventory inventory = inventory();
        JsonNode image = uploaded(inventory, picture("jpg", 60, 40), "spoof.png", "application/octet-stream", "Bedroom");
        assertThat(image.get("contentType").asText()).isEqualTo("image/jpeg");
        var bytes = binary(image.get("contentUrl").asText(), inventory.owner.token, 200);
        assertThat(ImageIO.read(new ByteArrayInputStream(bytes.body())).getHeight()).isEqualTo(40);
        binary(image.get("contentUrl").asText(), null, 401);
        binary(publicImage(inventory, image), null, 404);
        publish(inventory, 0, List.of(), 200);
        assertThat(node(call("GET", inventory.publicListing(), null, null, 200)).get("images").size()).isZero();
        binary(publicImage(inventory, image), null, 404);
    }

    @Test
    void phoneJpegOrientationIsAppliedBeforeExifIsRemoved() throws Exception {
        Inventory inventory = inventory();
        byte[] jpeg = picture("jpg", 60, 40);
        byte[] exif = new byte[]{'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        var source = new ByteArrayOutputStream();
        source.write(jpeg, 0, 2); source.write(new byte[]{(byte)255,(byte)225,0,34}); source.write(exif); source.write(jpeg, 2, jpeg.length - 2);
        JsonNode image = uploaded(inventory, source.toByteArray(), "phone.jpg", "image/jpeg", "Phone photo");
        assertThat(image.get("width").asInt()).isEqualTo(40);
        assertThat(image.get("height").asInt()).isEqualTo(60);
        var content = binary(image.get("contentUrl").asText(), inventory.owner.token, 200);
        assertThat(new String(content.body(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void publicationIncludesOnlySelectedImagesInTheirApprovedOrder() throws Exception {
        Inventory inventory = inventory();
        JsonNode first = uploaded(inventory, picture("png", 30, 20), "first.png", "image/png", "First");
        JsonNode second = uploaded(inventory, picture("png", 30, 20), "second.png", "image/png", "Second");
        JsonNode privateImage = uploaded(inventory, picture("png", 30, 20), "private.png", "image/png", "Private caption");
        publish(inventory, 0, List.of(reference(second, 0), reference(first, 0)), 200);
        JsonNode listing = node(call("GET", inventory.publicListing(), null, null, 200));
        assertThat(listing.get("images").get(0).get("id").asText()).isEqualTo(second.get("id").asText());
        assertThat(listing.get("images").get(1).get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(listing.toString()).doesNotContain("Private caption", "storageKey", IMAGE_ROOT.toString(), "image_version", "version");
        binary(publicImage(inventory, first), null, 200);
        binary(publicImage(inventory, privateImage), null, 404);
        call("POST", inventory.listingPath() + "/withdraw", Map.of("version", 1), inventory.owner.token, 200);
        binary(publicImage(inventory, first), null, 404);
    }

    @Test
    void selectedImageChangesAndArchivalHidePublicationUntilReviewedAgain() throws Exception {
        Inventory inventory = inventory();
        JsonNode image = uploaded(inventory, picture("png", 30, 20), "image.png", "image/png", "Old caption");
        publish(inventory, 0, List.of(reference(image, 0)), 200);
        call("PUT", imagePath(inventory, image), Map.of("version", 0, "altText", "New caption"), inventory.owner.token, 200);
        call("GET", inventory.publicListing(), null, null, 404);
        binary(publicImage(inventory, image), null, 404);
        publish(inventory, 1, List.of(reference(image, 0)), 409);
        publish(inventory, 1, List.of(reference(image, 1)), 200);
        assertThat(node(call("GET", inventory.publicListing(), null, null, 200)).get("images").get(0).get("altText").asText()).isEqualTo("New caption");
        call("PATCH", imagePath(inventory, image) + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 200);
        binary(publicImage(inventory, image), null, 404);
        binary(image.get("contentUrl").asText(), inventory.owner.token, 404);
        assertThat(node(call("GET", inventory.images(), null, inventory.owner.token, 200)).size()).isZero();
        assertThat(node(call("GET", inventory.images() + "?includeArchived=true", null, inventory.owner.token, 200)).size()).isEqualTo(1);
        call("PATCH", imagePath(inventory, image) + "/status", Map.of("version", 2, "status", "ACTIVE"), inventory.owner.token, 200);
        call("GET", inventory.publicListing(), null, null, 404);
        publish(inventory, 2, List.of(reference(image, 3)), 200);
        binary(publicImage(inventory, image), null, 200);
    }

    @Test
    void unselectedImagesDoNotChangeAnExistingPublication() throws Exception {
        Inventory inventory = inventory();
        JsonNode selected = uploaded(inventory, picture("png", 30, 20), "selected.png", "image/png", "Selected");
        publish(inventory, 0, List.of(reference(selected, 0)), 200);
        JsonNode privateImage = uploaded(inventory, picture("png", 30, 20), "new-private.png", "image/png", "Private");
        call("PUT", imagePath(inventory, privateImage), Map.of("version", 0, "altText", "Updated private caption"), inventory.owner.token, 200);
        call("GET", inventory.publicListing(), null, null, 200);
        binary(publicImage(inventory, selected), null, 200);
        binary(publicImage(inventory, privateImage), null, 404);
    }

    @Test
    void guestsOutsidersAndOrdinaryMembersCannotManagePrivateImages() throws Exception {
        Inventory inventory = inventory();
        Account member = account();
        Account outsider = account();
        call("POST", "/api/v1/organizations/" + inventory.organization + "/members", Map.of("email", member.email, "role", "MEMBER"), inventory.owner.token, 201);
        JsonNode image = uploaded(inventory, picture("png", 30, 20), "image.png", "image/png", "Private");
        call("GET", inventory.images(), null, null, 401);
        call("GET", inventory.images(), null, outsider.token, 404);
        call("GET", inventory.images(), null, member.token, 200);
        binary(image.get("contentUrl").asText(), outsider.token, 404);
        binary(image.get("contentUrl").asText(), member.token, 200);
        assertThat(upload(inventory.images(), picture("png", 30, 20), "image.png", "image/png", "", member.token).statusCode()).isEqualTo(403);
        assertThat(upload(inventory.images(), picture("png", 30, 20), "image.png", "image/png", "", null).statusCode()).isEqualTo(401);
        call("PUT", imagePath(inventory, image), Map.of("version", 0, "altText", "Denied"), member.token, 403);
        call("PATCH", imagePath(inventory, image) + "/status", Map.of("version", 0, "status", "ARCHIVED"), outsider.token, 404);
    }

    @Test
    void delegatedAgenciesCanUploadAndLoseAccessImmediatelyWhenRevoked() throws Exception {
        Inventory inventory = inventory();
        Account agency = account();
        String organization = organization(agency, "AGENCY");
        call("PATCH", inventory.propertyPath() + "/management", Map.of("version", 1, "managerOrganizationId", organization), inventory.owner.token, 200);
        String base = "/api/v1/organizations/" + organization + "/properties/" + inventory.property + "/images";
        var uploaded = upload(base, picture("png", 30, 20), "image.png", "image/png", "Agency caption", agency.token);
        assertThat(uploaded.statusCode()).isEqualTo(201);
        JsonNode image = node(uploaded);
        String agencyContent = image.get("contentUrl").asText();
        binary(agencyContent, agency.token, 200);
        Map<String, Object> revoke = new java.util.HashMap<>(); revoke.put("version", 2); revoke.put("managerOrganizationId", null);
        call("PATCH", inventory.propertyPath() + "/management", revoke, inventory.owner.token, 200);
        binary(agencyContent, agency.token, 404);
        assertThat(upload(base, picture("png", 30, 20), "image.png", "image/png", "", agency.token).statusCode()).isEqualTo(404);
        binary(inventory.images() + "/" + image.get("id").asText() + "/content", inventory.owner.token, 200);
    }

    @Test
    void publicationRejectsForeignDuplicateArchivedAndStaleImageSelections() throws Exception {
        Inventory first = inventory();
        Inventory second = inventory();
        JsonNode image = uploaded(first, picture("png", 30, 20), "first.png", "image/png", "First");
        JsonNode foreign = uploaded(second, picture("png", 30, 20), "second.png", "image/png", "Second");
        publish(first, 0, List.of(reference(foreign, 0)), 404);
        publish(first, 0, List.of(reference(image, 0), reference(image, 0)), 400);
        publish(first, 0, List.of(reference(image, -1)), 400);
        publish(first, 0, List.of(reference(image, 1)), 409);
        publish(first, 0, java.util.Collections.nCopies(21, reference(image, 0)), 400);
        call("PATCH", imagePath(first, image) + "/status", Map.of("version", 0, "status", "ARCHIVED"), first.owner.token, 200);
        publish(first, 0, List.of(reference(image, 1)), 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_image_references WHERE listing_id = ?", Integer.class, UUID.fromString(first.listing))).isZero();
        call("GET", first.publicListing(), null, null, 404);
    }

    @Test
    void invalidOversizedAndExcessivePixelUploadsLeaveNoRecordsOrFiles() throws Exception {
        Inventory inventory = inventory();
        long before = fileCount();
        for (byte[] invalid : List.of(new byte[0], "<svg onload='bad()'/>".getBytes(StandardCharsets.UTF_8), new byte[]{1, 2, 3})) {
            assertThat(upload(inventory.images(), invalid, "spoof.jpg", "image/jpeg", "", inventory.owner.token).statusCode()).isEqualTo(400);
        }
        assertThat(upload(inventory.images(), picture("png", 3500, 3500), "huge.png", "image/png", "", inventory.owner.token).statusCode()).isEqualTo(400);
        assertThat(upload(inventory.images(), new byte[10 * 1024 * 1024 + 1], "large.png", "image/png", "", inventory.owner.token).statusCode()).isEqualTo(413);
        assertThat(upload(inventory.images(), picture("png", 30, 20), "image.png", "image/png", "x".repeat(201), inventory.owner.token).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM property_images WHERE property_id = ?", Integer.class, UUID.fromString(inventory.property))).isZero();
        assertThat(fileCount()).isEqualTo(before);
    }

    @Test
    void auditFailureRollsBackTheImageRowAndRemovesItsNewFile() throws Exception {
        Inventory inventory = inventory();
        long before = fileCount();
        String constraint = "reject_image_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT " + constraint + " CHECK (action <> 'PROPERTY_IMAGE_UPLOADED') NOT VALID");
        try {
            assertThat(upload(inventory.images(), picture("png", 30, 20), "image.png", "image/png", "", inventory.owner.token).statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM property_images WHERE property_id = ?", Integer.class, UUID.fromString(inventory.property))).isZero();
            assertThat(fileCount()).isEqualTo(before);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint); }
    }

    @Test
    void concurrentCaptionEditsRejectTheStaleWriter() throws Exception {
        Inventory inventory = inventory();
        JsonNode image = uploaded(inventory, picture("png", 30, 20), "image.png", "image/png", "Original");
        var first = client.sendAsync(request("PUT", imagePath(inventory, image), Map.of("version", 0, "altText", "First"), inventory.owner.token), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("PUT", imagePath(inventory, image), Map.of("version", 0, "altText", "Second"), inventory.owner.token), HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{first.get(20, TimeUnit.SECONDS).statusCode(), second.get(20, TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void oldPublicationSnapshotsWithoutImagesStillDeserialize() throws Exception {
        Inventory inventory = inventory();
        publish(inventory, 0, List.of(), 200);
        jdbc.update("UPDATE rental_listings SET public_snapshot = public_snapshot - 'images' WHERE id = ?", UUID.fromString(inventory.listing));
        assertThat(node(call("GET", inventory.publicListing(), null, null, 200)).get("images").size()).isZero();
    }

    private Inventory inventory() throws Exception {
        Account owner = account(); String organization = organization(owner, "LANDLORD");
        String base = "/api/v1/organizations/" + organization + "/properties";
        String property = node(call("POST", base, Map.of("name", "Image test property", "type", "HOUSE", "countryCode", "KE", "city", "Nairobi", "address", "Private address"), owner.token, 201)).get("id").asText();
        String propertyPath = base + "/" + property;
        call("PATCH", propertyPath + "/status", Map.of("version", 0, "status", "ACTIVE"), owner.token, 200);
        String unit = node(call("POST", propertyPath + "/units", Map.of("label", "A", "bedrooms", 2, "bathrooms", 1, "monthlyRent", "25000", "deposit", "50000", "currency", "KES", "availability", "AVAILABLE"), owner.token, 201)).get("id").asText();
        String listing = node(call("POST", propertyPath + "/listings", Map.of("unitId", unit, "details", Map.of("title", "Public rental", "description", "Listing for image integration", "showExactLocation", false)), owner.token, 201)).get("id").asText();
        return new Inventory(owner, organization, property, listing);
    }
    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode user = node(call("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Image test"), null, 201));
        JsonNode login = node(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200));
        return new Account(UUID.fromString(user.get("id").asText()), email, login.get("accessToken").asText());
    }
    private String organization(Account actor, String type) throws Exception { return node(call("POST", "/api/v1/organizations", Map.of("name", "Image test org", "type", type), actor.token, 201)).get("id").asText(); }
    private JsonNode uploaded(Inventory inventory, byte[] data, String name, String type, String caption) throws Exception {
        var response = upload(inventory.images(), data, name, type, caption, inventory.owner.token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return node(response);
    }
    private HttpResponse<String> upload(String path, byte[] data, String name, String type, String caption, String token) throws Exception {
        String boundary = "image" + UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"altText\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + caption + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
    private byte[] picture(String format, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (var bytes = new ByteArrayOutputStream()) { ImageIO.write(image, format, bytes); image.flush(); return bytes.toByteArray(); }
    }
    private byte[] pngWithMetadata(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var metadata = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(image), writer.getDefaultWriteParam());
            var tree = new IIOMetadataNode("javax_imageio_png_1.0"); var text = new IIOMetadataNode("tEXt");
            var entry = new IIOMetadataNode("tEXtEntry"); entry.setAttribute("keyword", "Comment"); entry.setAttribute("value", "PRIVATE_GPS_COMMENT");
            text.appendChild(entry); tree.appendChild(text); metadata.mergeTree("javax_imageio_png_1.0", tree);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam()); output.flush(); image.flush(); return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
    private long fileCount() throws Exception { try (var files = Files.list(IMAGE_ROOT)) { return files.count(); } }
    private Map<String, Object> reference(JsonNode image, long version) { return Map.of("id", image.get("id").asText(), "version", version); }
    private void publish(Inventory inventory, long version, List<?> selections, int status) throws Exception {
        call("POST", inventory.listingPath() + "/publish", Map.of("version", version, "propertyVersion", 1, "unitVersion", 0, "images", selections), inventory.owner.token, status);
    }
    private String imagePath(Inventory inventory, JsonNode image) { return inventory.images() + "/" + image.get("id").asText(); }
    private String publicImage(Inventory inventory, JsonNode image) { return inventory.publicListing() + "/images/" + image.get("id").asText(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpRequest request(String method, String path, Object body, String token) {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private HttpResponse<String> call(String method, String path, Object body, String token, int status) throws Exception {
        var response = client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status); return response;
    }
    private HttpResponse<byte[]> binary(String path, String token, int status) throws Exception {
        var response = client.send(request("GET", path, null, token), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("Image content %s", path).isEqualTo(status); return response;
    }
    private record Account(UUID id, String email, String token) { }
    private record Inventory(Account owner, String organization, String property, String listing) {
        String propertyPath() { return "/api/v1/organizations/" + organization + "/properties/" + property; }
        String images() { return propertyPath() + "/images"; }
        String listingPath() { return propertyPath() + "/listings/" + listing; }
        String publicListing() { return "/api/v1/listings/" + listing; }
    }
}
