package com.zaza.housing;

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
import com.zaza.housing.util.MediaProcessRunner;
import org.springframework.mock.web.MockMultipartFile;
import com.zaza.housing.service.VideoProcessingService;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.ProcessedVideo;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
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
class PropertyVideoTests {
    private static final String PASSWORD = "Video-test-password-42";
    private static final Path VIDEO_ROOT = Path.of(".work", "video-tests-" + UUID.randomUUID()).toAbsolutePath();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PausingVideoProcessor pausing;
    @DynamicPropertySource static void videos(DynamicPropertyRegistry registry) { registry.add("zaza.videos.directory", () -> VIDEO_ROOT.toString()); }


    @Test
    void realMovIsConvertedToBrowserMp4AndPrivateMetadataIsRemoved() throws Exception {
        Inventory inventory = inventory();
        byte[] original = clip(1920, 1080, 1, true, "mov");
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("PRIVATE_LOCATION");
        JsonNode video = uploaded(inventory, original, "../../escape.html", "text/html", "Walkthrough");
        assertThat(video.get("width").asInt()).isEqualTo(1280);
        assertThat(video.get("height").asInt()).isEqualTo(720);
        assertThat(video.get("durationSeconds").asDouble()).isBetween(0.9, 1.1);
        assertThat(video.get("contentType").asText()).isEqualTo("video/mp4");
        assertThat(video.toString()).doesNotContain("storageKey", "escape", "PRIVATE_LOCATION", VIDEO_ROOT.toString());
        var response = binary(video.get("contentUrl").asText(), inventory.owner.token, 200);
        assertThat(response.body().length).isEqualTo(video.get("byteSize").asInt());
        assertThat(new String(response.body(), StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_LOCATION");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).isEqualTo("video/mp4");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        Path output = Files.createTempFile(VIDEO_ROOT.getParent(), "verified-video-", ".mp4");
        try {
            Files.write(output, response.body());
            JsonNode probe = json.readTree(MediaProcessRunner.run(List.of(ffprobe(), "-v", "error", "-show_streams", "-show_format", "-of", "json", output.toString()), Duration.ofSeconds(10)));
            assertThat(probe.get("streams").get(0).get("codec_name").asText()).isEqualTo("h264");
            assertThat(probe.get("streams").get(0).get("pix_fmt").asText()).isEqualTo("yuv420p");
            assertThat(probe.get("streams").get(1).get("codec_name").asText()).isEqualTo("aac");
        } finally { Files.deleteIfExists(output); }
        String key = jdbc.queryForObject("SELECT storage_key FROM property_videos WHERE id = ?", String.class, UUID.fromString(video.get("id").asText()));
        assertThat(key).matches("[0-9a-f-]{36}\\.mp4");
    }

    @Test
    void privateAndSelectedPublicVideosSupportSeekingAndInvalidRanges() throws Exception {
        Inventory inventory = inventory();
        JsonNode video = uploaded(inventory, shortClip(), "phone.mp4", "video/mp4", "Private");
        String path = video.get("contentUrl").asText();
        byte[] full = binary(path, inventory.owner.token, 200).body();
        var partial = range(path, inventory.owner.token, "bytes=10-99", 206);
        assertThat(partial.body()).isEqualTo(java.util.Arrays.copyOfRange(full, 10, 100));
        assertThat(partial.headers().firstValue("Content-Range").orElse("")).isEqualTo("bytes 10-99/" + full.length);
        assertThat(range(path, inventory.owner.token, "bytes=-16", 206).body()).isEqualTo(java.util.Arrays.copyOfRange(full, full.length - 16, full.length));
        range(path, inventory.owner.token, "bytes=" + full.length + "-", 416);
        range(path, null, "bytes=0-9", 401);
        range(publicVideo(inventory, video), null, "bytes=0-9", 404);
        publish(inventory, 0, List.of(reference(video, 0)), 200);
        assertThat(range(publicVideo(inventory, video), null, "bytes=0-99", 206).body()).isEqualTo(java.util.Arrays.copyOfRange(full, 0, 100));
        call("POST", inventory.listingPath() + "/withdraw", Map.of("version", 1), inventory.owner.token, 200);
        range(publicVideo(inventory, video), null, "bytes=0-99", 404);
    }

    @Test
    void onlyReviewedVideosArePublishedInSelectedOrder() throws Exception {
        Inventory inventory = inventory();
        JsonNode first = uploaded(inventory, shortClip(), "first.mp4", "video/mp4", "First");
        JsonNode second = uploaded(inventory, shortClip(), "second.mp4", "video/mp4", "Second");
        JsonNode hidden = uploaded(inventory, shortClip(), "hidden.mp4", "video/mp4", "Private caption");
        publish(inventory, 0, List.of(reference(second, 0), reference(first, 0)), 200);
        JsonNode listing = node(call("GET", inventory.publicListing(), null, null, 200));
        assertThat(listing.get("videos").get(0).get("id").asText()).isEqualTo(second.get("id").asText());
        assertThat(listing.get("videos").get(1).get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(listing.toString()).doesNotContain("Private caption", "storageKey", "propertyId", "version");
        binary(publicVideo(inventory, hidden), null, 404);
        call("PUT", videoPath(inventory, hidden), Map.of("version", 0, "caption", "Still private"), inventory.owner.token, 200);
        call("GET", inventory.publicListing(), null, null, 200);
    }

    @Test
    void selectedVideoEditsArchiveAndRestoreRequireReviewBeforeRepublication() throws Exception {
        Inventory inventory = inventory();
        JsonNode video = uploaded(inventory, shortClip(), "phone.mp4", "video/mp4", "Old caption");
        publish(inventory, 0, List.of(reference(video, 0)), 200);
        call("PUT", videoPath(inventory, video), Map.of("version", 0, "caption", "New caption"), inventory.owner.token, 200);
        call("GET", inventory.publicListing(), null, null, 404);
        assertThat(node(call("GET", "/api/v1/listings?q=Public+rental", null, null, 200)).toString()).doesNotContain(inventory.listing);
        binary(publicVideo(inventory, video), null, 404);
        publish(inventory, 1, List.of(reference(video, 0)), 409);
        publish(inventory, 1, List.of(reference(video, 1)), 200);
        assertThat(node(call("GET", inventory.publicListing(), null, null, 200)).get("videos").get(0).get("caption").asText()).isEqualTo("New caption");
        call("PATCH", videoPath(inventory, video) + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 200);
        binary(publicVideo(inventory, video), null, 404);
        binary(video.get("contentUrl").asText(), inventory.owner.token, 404);
        assertThat(node(call("GET", inventory.videos(), null, inventory.owner.token, 200)).size()).isZero();
        assertThat(node(call("GET", inventory.videos() + "?includeArchived=true", null, inventory.owner.token, 200)).size()).isEqualTo(1);
        call("PATCH", videoPath(inventory, video) + "/status", Map.of("version", 2, "status", "ACTIVE"), inventory.owner.token, 200);
        call("GET", inventory.publicListing(), null, null, 404);
        publish(inventory, 2, List.of(reference(video, 3)), 200);
        binary(publicVideo(inventory, video), null, 200);
    }

    @Test
    void guestsOutsidersAndUnassignedMembersCannotReadVideos() throws Exception {
        Inventory inventory = inventory(); Account member = account(); Account outsider = account();
        call("POST", "/api/v1/organizations/" + inventory.organization + "/members", Map.of("email", member.email, "role", "MEMBER"), inventory.owner.token, 201);
        JsonNode video = uploaded(inventory, shortClip(), "phone.mp4", "video/mp4", "Private");
        call("GET", inventory.videos(), null, null, 401);
        call("GET", inventory.videos(), null, outsider.token, 404);
        binary(video.get("contentUrl").asText(), member.token, 404);
        assertThat(upload(inventory.videos(), shortClip(), "phone.mp4", "video/mp4", "", member.token).statusCode()).isEqualTo(403);
        assertThat(upload(inventory.videos(), shortClip(), "phone.mp4", "video/mp4", "", null).statusCode()).isEqualTo(401);
        call("PUT", videoPath(inventory, video), Map.of("version", 0, "caption", "Denied"), member.token, 403);
        call("PATCH", videoPath(inventory, video) + "/status", Map.of("version", 0, "status", "ARCHIVED"), outsider.token, 404);
    }

    @Test
    void delegatedAgencyAccessIsRevokedImmediately() throws Exception {
        Inventory inventory = inventory(); Account agency = account(); String organization = organization(agency, "AGENCY");
        call("PATCH", inventory.propertyPath() + "/management", Map.of("version", 1, "managerOrganizationId", organization), inventory.owner.token, 200);
        String base = "/api/v1/organizations/" + organization + "/properties/" + inventory.property + "/videos";
        var response = upload(base, shortClip(), "phone.mp4", "video/mp4", "Agency", agency.token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); JsonNode video = node(response);
        binary(video.get("contentUrl").asText(), agency.token, 200);
        Map<String, Object> revoke = new java.util.HashMap<>(); revoke.put("version", 2); revoke.put("managerOrganizationId", null);
        call("PATCH", inventory.propertyPath() + "/management", revoke, inventory.owner.token, 200);
        binary(video.get("contentUrl").asText(), agency.token, 404);
        assertThat(upload(base, shortClip(), "phone.mp4", "video/mp4", "", agency.token).statusCode()).isEqualTo(404);
        binary(inventory.videos() + "/" + video.get("id").asText() + "/content", inventory.owner.token, 200);
    }

    @Test
    void publicationRejectsForeignDuplicateArchivedAndStaleSelections() throws Exception {
        Inventory first = inventory(); Inventory second = inventory();
        JsonNode video = uploaded(first, shortClip(), "one.mp4", "video/mp4", "First");
        JsonNode foreign = uploaded(second, shortClip(), "two.mp4", "video/mp4", "Second");
        publish(first, 0, List.of(reference(foreign, 0)), 404);
        publish(first, 0, List.of(reference(video, 0), reference(video, 0)), 400);
        publish(first, 0, List.of(reference(video, -1)), 400);
        publish(first, 0, List.of(reference(video, 1)), 409);
        publish(first, 0, java.util.Collections.nCopies(6, reference(video, 0)), 400);
        call("PATCH", videoPath(first, video) + "/status", Map.of("version", 0, "status", "ARCHIVED"), first.owner.token, 200);
        publish(first, 0, List.of(reference(video, 1)), 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_video_references WHERE listing_id = ?", Integer.class, UUID.fromString(first.listing))).isZero();
    }

    @Test
    void invalidOversizedLongAndHighResolutionClipsLeaveNoFilesOrRows() throws Exception {
        Inventory inventory = inventory(); long before = fileCount();
        for (byte[] invalid : List.of(new byte[0], "<script>bad()</script>".getBytes(StandardCharsets.UTF_8), new byte[]{0,0,0,24,'f','t','y','p','q','t',' ',' '})) {
            assertThat(upload(inventory.videos(), invalid, "spoof.mp4", "video/mp4", "", inventory.owner.token).statusCode()).isEqualTo(400);
        }
        assertThat(upload(inventory.videos(), new byte[50 * 1024 * 1024 + 1], "large.mp4", "video/mp4", "", inventory.owner.token).statusCode()).isEqualTo(413);
        assertThat(upload(inventory.videos(), clip(32, 32, 121, false, "mp4"), "long.mp4", "video/mp4", "", inventory.owner.token).statusCode()).isEqualTo(400);
        assertThat(upload(inventory.videos(), clip(4200, 32, 0.2, false, "mp4"), "wide.mp4", "video/mp4", "", inventory.owner.token).statusCode()).isEqualTo(400);
        assertThat(upload(inventory.videos(), shortClip(), "phone.mp4", "video/mp4", "x".repeat(201), inventory.owner.token).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM property_videos WHERE property_id = ?", Integer.class, UUID.fromString(inventory.property))).isZero();
        assertThat(fileCount()).isEqualTo(before);
    }

    @Test
    void auditFailureRollsBackVideoAndRemovesItsNewFile() throws Exception {
        Inventory inventory = inventory(); long before = fileCount();
        String constraint = "reject_video_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT " + constraint + " CHECK (action <> 'PROPERTY_VIDEO_UPLOADED') NOT VALID");
        try {
            assertThat(upload(inventory.videos(), shortClip(), "phone.mp4", "video/mp4", "", inventory.owner.token).statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM property_videos WHERE property_id = ?", Integer.class, UUID.fromString(inventory.property))).isZero();
            assertThat(fileCount()).isEqualTo(before);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint); }
    }

    @Test
    void capacityAndConcurrentCaptionEditsAreEnforced() throws Exception {
        Inventory inventory = inventory();
        JsonNode first = null;
        for (int index = 0; index < 5; index++) {
            JsonNode video = uploaded(inventory, shortClip(), "phone.mp4", "video/mp4", "Caption");
            if (index == 0) first = video;
        }
        assertThat(upload(inventory.videos(), shortClip(), "phone.mp4", "video/mp4", "", inventory.owner.token).statusCode()).isEqualTo(409);
        var one = client.sendAsync(request("PUT", videoPath(inventory, first), Map.of("version", 0, "caption", "One"), inventory.owner.token), HttpResponse.BodyHandlers.ofString());
        var two = client.sendAsync(request("PUT", videoPath(inventory, first), Map.of("version", 0, "caption", "Two"), inventory.owner.token), HttpResponse.BodyHandlers.ofString());
        assertThat(new int[]{one.get(20, TimeUnit.SECONDS).statusCode(), two.get(20, TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(200, 409);
        call("PATCH", videoPath(inventory, first) + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 200);
        uploaded(inventory, shortClip(), "phone.mp4", "video/mp4", "Replacement");
        call("PATCH", videoPath(inventory, first) + "/status", Map.of("version", 2, "status", "ACTIVE"), inventory.owner.token, 409);
    }

    @Test
    void olderSnapshotsAndRequestsWithoutVideosRemainCompatible() throws Exception {
        Inventory inventory = inventory();
        call("POST", inventory.listingPath() + "/publish", Map.of("version", 0, "propertyVersion", 1, "unitVersion", 0), inventory.owner.token, 200);
        jdbc.update("UPDATE rental_listings SET public_snapshot = public_snapshot - 'videos' WHERE id = ?", UUID.fromString(inventory.listing));
        assertThat(node(call("GET", inventory.publicListing(), null, null, 200)).get("videos").size()).isZero();
    }

    @Test
    void unavailableToolsReturnServiceUnavailableAndCleanWorkspace() throws Exception {
        var processing = new VideoProcessingService("missing-ffmpeg", "missing-ffprobe", json);
        Path workspace = Files.createTempDirectory(VIDEO_ROOT, ".processing-");
        try {
            assertThatThrownBy(() -> processing.process(new MockMultipartFile("file", shortClip()), workspace))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        } finally { Files.deleteIfExists(workspace.resolve("source.upload")); Files.deleteIfExists(workspace); }
    }

    @Test
    void corsAllowsBrowserRangeRequests() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/v1/listings/" + UUID.randomUUID() + "/videos/" + UUID.randomUUID()))
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Range").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")).containsIgnoringCase("Range");
    }


    @Test
    void permissionsAreRecheckedAfterEncodingAndNoPropertyLocksAreHeld() throws Exception {
        Inventory inventory = inventory(); long before = fileCount();
        pausing.pause();
        var result = CompletableFuture.supplyAsync(() -> {
            try { return upload(inventory.videos(), shortClip(), "phone.mp4", "video/mp4", "", inventory.owner.token); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        try {
            assertThat(pausing.ready.await(20, TimeUnit.SECONDS)).as("real conversion reached its persistence boundary").isTrue();
            // This request completes while the encoder is paused, proving it holds no property lock.
            call("PATCH", inventory.propertyPath() + "/status", Map.of("version", 1, "status", "ARCHIVED"), inventory.owner.token, 200);
        } finally { pausing.resume(); }
        assertThat(result.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM property_videos WHERE property_id = ?", Integer.class, UUID.fromString(inventory.property))).isZero();
        assertThat(fileCount()).isEqualTo(before);
    }

    @Test
    void stalledMediaProcessIsTerminatedAtItsDeadline() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> MediaProcessRunner.run(List.of(ffmpeg(), "-nostdin", "-loglevel", "error", "-re", "-f", "lavfi",
                "-i", "testsrc=size=32x32:rate=1", "-t", "30", "-f", "null", "-"), Duration.ofMillis(250)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @TestConfiguration
    static class ProcessingTestConfiguration {
        @Bean @Primary
        PausingVideoProcessor pausingVideoProcessor(@Value("${zaza.videos.ffmpeg}") String ffmpeg,
                @Value("${zaza.videos.ffprobe}") String ffprobe, ObjectMapper json) {
            return new PausingVideoProcessor(ffmpeg, ffprobe, json);
        }
    }
    static class PausingVideoProcessor extends VideoProcessingService {
        volatile CountDownLatch ready;
        volatile CountDownLatch release;
        PausingVideoProcessor(String ffmpeg, String ffprobe, ObjectMapper json) { super(ffmpeg, ffprobe, json); }
        void pause() { ready = new CountDownLatch(1); release = new CountDownLatch(1); }
        void resume() { release.countDown(); }
        @Override public ProcessedVideo process(MultipartFile file, Path workspace) throws IOException {
            ProcessedVideo result = super.process(file, workspace);
            CountDownLatch gate = release;
            if (gate != null && gate.getCount() != 0) {
                ready.countDown();
                try { if (!gate.await(20, TimeUnit.SECONDS)) throw new IOException("Test encoding gate timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
            return result;
        }
    }

    private HttpResponse<byte[]> range(String path, String token, String range, int status) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).header("Range", range);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("Range %s: %s", range, new String(response.body(), StandardCharsets.ISO_8859_1)).isEqualTo(status);
        return response;
    }
    private String ffmpeg() { return System.getenv().getOrDefault("ZAZA_FFMPEG", ".tools/ffmpeg/bin/ffmpeg.exe"); }
    private String ffprobe() { return System.getenv().getOrDefault("ZAZA_FFPROBE", ".tools/ffmpeg/bin/ffprobe.exe"); }
    private byte[] shortClip() throws Exception { return clip(64, 48, 0.3, false, "mp4"); }
    private byte[] clip(int width, int height, double seconds, boolean audio, String format) throws Exception {
        Path source = Files.createTempFile(VIDEO_ROOT.getParent(), "video-fixture-", "." + format);
        try {
            var args = new java.util.ArrayList<>(List.of(ffmpeg(), "-nostdin", "-hide_banner", "-loglevel", "error", "-filter_threads", "1",
                    "-f", "lavfi", "-i", "testsrc=size=" + width + "x" + height + ":rate=10"));
            if (audio) args.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"));
            args.addAll(List.of("-t", String.valueOf(seconds), "-c:v", "libx264", "-threads", "2", "-preset", "ultrafast", "-pix_fmt", "yuv420p"));
            if (audio) args.addAll(List.of("-c:a", "aac"));
            args.addAll(List.of("-metadata", "comment=PRIVATE_LOCATION", "-metadata", "location=+01.23+004.56/", "-y", source.toString()));
            MediaProcessRunner.run(args, Duration.ofSeconds(30));
            return Files.readAllBytes(source);
        } finally { Files.deleteIfExists(source); }
    }
    private Inventory inventory() throws Exception {
        Account owner = account(); String organization = organization(owner, "LANDLORD");
        String base = "/api/v1/organizations/" + organization + "/properties";
        String property = node(call("POST", base, Map.of("name", "Video test property", "type", "HOUSE", "countryCode", "KE", "city", "Nairobi", "address", "Private address"), owner.token, 201)).get("id").asText();
        String propertyPath = base + "/" + property;
        call("PATCH", propertyPath + "/status", Map.of("version", 0, "status", "ACTIVE"), owner.token, 200);
        String unit = node(call("POST", propertyPath + "/units", Map.of("label", "A", "bedrooms", 2, "bathrooms", 1, "monthlyRent", "25000", "deposit", "50000", "currency", "KES", "availability", "AVAILABLE"), owner.token, 201)).get("id").asText();
        String listing = node(call("POST", propertyPath + "/listings", Map.of("unitId", unit, "details", Map.of("title", "Public rental", "description", "Listing for video integration", "showExactLocation", false)), owner.token, 201)).get("id").asText();
        return new Inventory(owner, organization, property, listing);
    }
    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode user = node(call("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Video test"), null, 201));
        JsonNode login = node(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200));
        return new Account(UUID.fromString(user.get("id").asText()), email, login.get("accessToken").asText());
    }
    private String organization(Account actor, String type) throws Exception { return node(call("POST", "/api/v1/organizations", Map.of("name", "Video test org", "type", type), actor.token, 201)).get("id").asText(); }
    private JsonNode uploaded(Inventory inventory, byte[] data, String name, String type, String caption) throws Exception {
        var response = upload(inventory.videos(), data, name, type, caption, inventory.owner.token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return node(response);
    }
    private HttpResponse<String> upload(String path, byte[] data, String name, String type, String caption, String token) throws Exception {
        String boundary = "video" + UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"caption\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + caption + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
    private long fileCount() throws Exception { try (var files = Files.list(VIDEO_ROOT)) { return files.count(); } }
    private Map<String, Object> reference(JsonNode image, long version) { return Map.of("id", image.get("id").asText(), "version", version); }
    private void publish(Inventory inventory, long version, List<?> selections, int status) throws Exception {
        call("POST", inventory.listingPath() + "/publish", Map.of("version", version, "propertyVersion", 1, "unitVersion", 0, "videos", selections), inventory.owner.token, status);
    }
    private String videoPath(Inventory inventory, JsonNode image) { return inventory.videos() + "/" + image.get("id").asText(); }
    private String publicVideo(Inventory inventory, JsonNode image) { return inventory.publicListing() + "/videos/" + image.get("id").asText(); }
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
        assertThat(response.statusCode()).as("Video content %s", path).isEqualTo(status); return response;
    }
    private record Account(UUID id, String email, String token) { }
    private record Inventory(Account owner, String organization, String property, String listing) {
        String propertyPath() { return "/api/v1/organizations/" + organization + "/properties/" + property; }
        String videos() { return propertyPath() + "/videos"; }
        String listingPath() { return propertyPath() + "/listings/" + listing; }
        String publicListing() { return "/api/v1/listings/" + listing; }
    }
}
