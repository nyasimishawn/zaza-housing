package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.service.SecurityMailService;
import com.zaza.housing.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zaza.security.require-admin-mfa=true",
      "zaza.security.mail-mode=local",
      "zaza.security.keys=tests:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
      "zaza.security.active-key-id=tests",
      "zaza.security.mail-poll-ms=3600000"
    })
@ActiveProfiles("test")
abstract class AdministrationTestSupport {
  static final String PASSWORD = "Administration-test-password-42";
  static final String OPS = "/api/v1/admin/operations",
      SEC = "/api/v1/me/security",
      CASES = "/api/v1/me/support/cases";
  static final java.nio.file.Path FILES =
      java.nio.file.Path.of("build", "administration-tests-" + UUID.randomUUID()).toAbsolutePath();

  @DynamicPropertySource
  static void paths(DynamicPropertyRegistry r) {
    r.add("zaza.security.local-mail-directory", () -> FILES.resolve("mail").toString());
    r.add("zaza.documents.directory", () -> FILES.resolve("evidence").toString());
  }

  @Value("${local.server.port}")
  int port;

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired SecurityCryptography crypto;
  @Autowired SecurityMailService mail;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  record Account(UUID id, String email, String token, String secret, List<String> codes) {}

  HttpResponse<String> call(String method, String path, Object body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode ok(String method, String path, Object body, String token, int status) throws Exception {
    var response = call(method, path, body, token);
    assertThat(response.statusCode())
        .as("%s %s: %s", method, path, response.body())
        .isEqualTo(status);
    return response.body().isBlank() ? null : json.readTree(response.body());
  }

  Account user() throws Exception {
    return user(false);
  }

  Account user(boolean operator) throws Exception {
    String email = "ops-" + UUID.randomUUID() + "@example.test";
    var u =
        ok(
            "POST",
            "/api/v1/auth/register",
            Map.of("email", email, "password", PASSWORD, "displayName", "Operations test"),
            null,
            201);
    String token =
        ok("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200)
            .get("accessToken")
            .asText();
    UUID id = UUID.fromString(u.get("id").asText());
    if (!operator) return new Account(id, email, token, null, List.of());
    // Controlled operator provisioning is a fixture, never a public privilege-grant API.
    jdbc.update(
        "UPDATE app_users SET platform_role='PLATFORM_ADMIN',email_verified_at=now() WHERE id=?",
        id);
    var setup = ok("POST", SEC + "/mfa/enroll", Map.of("password", PASSWORD), token, 200);
    String secret = setup.get("secret").asText();
    var enabled =
        ok(
            "POST",
            SEC + "/mfa/enable",
            Map.of(
                "password",
                PASSWORD,
                "version",
                setup.get("version").asLong(),
                "code",
                Totp.code(secret, Instant.now().getEpochSecond() / 30, 6)),
            token,
            200);
    List<String> codes = new ArrayList<>();
    enabled.get("recoveryCodes").forEach(c -> codes.add(c.asText()));
    return new Account(id, email, token, secret, List.copyOf(codes));
  }

  Map<String, Object> command(JsonNode record) {
    var r = new HashMap<String, Object>();
    r.put("requestId", UUID.randomUUID().toString());
    r.put("version", record.get("version").asLong());
    r.put("reason", "Reviewed the current evidence and exact operation.");
    r.put("password", PASSWORD);
    return r;
  }

  Map<String, Object> message(JsonNode record, String text, boolean internal) {
    return Map.of(
        "requestId",
        UUID.randomUUID().toString(),
        "version",
        record.get("version").asLong(),
        "body",
        text,
        "internal",
        internal);
  }

  JsonNode createCase(Account user, String kind, UUID listing) throws Exception {
    var body = new HashMap<String, Object>();
    body.put("requestId", UUID.randomUUID());
    body.put("kind", kind);
    body.put("category", "RENTAL");
    body.put("title", "Help with a rental workflow");
    body.put(
        "description", "Please review the independent evidence about this synthetic rental issue.");
    body.put("consent", true);
    if (listing != null) body.put("listingId", listing);
    return ok("POST", CASES, body, user.token, 200);
  }

  JsonNode adminCase(Account a, String id) throws Exception {
    return ok("GET", OPS + "/support-cases/" + id, null, a.token, 200);
  }

  JsonNode caseCommand(Account a, JsonNode c, String action) throws Exception {
    return ok(
        "POST",
        OPS + "/support-cases/" + c.get("id").asText() + "/" + action,
        command(c),
        a.token,
        200);
  }

  String mailToken(Account a, String purpose) {
    var row =
        jdbc.queryForMap(
            "SELECT id,encrypted_message FROM security_mail_outbox WHERE user_id=? AND purpose=?"
                + " ORDER BY created_at DESC LIMIT 1",
            a.id,
            purpose);
    String body =
        new String(
            crypto.decrypt("MAIL|" + row.get("id"), (String) row.get("encrypted_message")),
            java.nio.charset.StandardCharsets.UTF_8);
    var matcher = java.util.regex.Pattern.compile("(?m)^[A-Za-z0-9_-]{43}$").matcher(body);
    assertThat(matcher.find()).isTrue();
    return matcher.group();
  }

  byte[] png() throws Exception {
    var out = new ByteArrayOutputStream();
    var image = new BufferedImage(12, 9, BufferedImage.TYPE_INT_RGB);
    image.setRGB(3, 4, 0x12abcd);
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  HttpResponse<byte[]> upload(
      String path, String token, UUID request, long version, String caption, boolean internal)
      throws Exception {
    String boundary = "EvidenceBoundary" + UUID.randomUUID();
    var out = new ByteArrayOutputStream();
    for (var field :
        Map.of(
                "requestId",
                request.toString(),
                "version",
                Long.toString(version),
                "caption",
                caption,
                "internal",
                Boolean.toString(internal))
            .entrySet())
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + field.getKey()
                  + "\"\r\n\r\n"
                  + field.getValue()
                  + "\r\n")
              .getBytes());
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"evidence.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes());
    out.write(png());
    out.write(("\r\n--" + boundary + "--\r\n").getBytes());
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  record ListingFixture(
      Account owner, String org, String property, String unit, String listing, String image) {}

  ListingFixture listing() throws Exception {
    var owner = user();
    String org =
        ok(
                "POST",
                "/api/v1/organizations",
                Map.of("name", "Moderation fixture", "type", "LANDLORD"),
                owner.token,
                201)
            .get("id")
            .asText();
    String base = "/api/v1/organizations/" + org + "/properties";
    var p =
        ok(
            "POST",
            base,
            Map.of(
                "name",
                "Review property",
                "description",
                "Synthetic apartment with an explicitly published rental offer.",
                "type",
                "APARTMENT",
                "countryCode",
                "KE",
                "city",
                "Nairobi",
                "address",
                "Test address"),
            owner.token,
            201);
    String property = p.get("id").asText();
    ok(
        "PATCH",
        base + "/" + property + "/status",
        Map.of("version", 0, "status", "ACTIVE"),
        owner.token,
        200);
    String path = base + "/" + property;
    String unit =
        ok(
                "POST",
                path + "/units",
                Map.of(
                    "label",
                    "A1",
                    "bedrooms",
                    2,
                    "bathrooms",
                    1,
                    "floorAreaSqm",
                    75,
                    "monthlyRent",
                    30000,
                    "deposit",
                    30000,
                    "currency",
                    "KES",
                    "availability",
                    "AVAILABLE"),
                owner.token,
                201)
            .get("id")
            .asText();
    var response =
        upload(path + "/images", owner.token, UUID.randomUUID(), 0, "Review test exterior", false);
    assertThat(response.statusCode()).isEqualTo(201);
    String image = json.readTree(response.body()).get("id").asText();
    var listing =
        ok(
            "POST",
            path + "/listings",
            Map.of(
                "unitId",
                unit,
                "details",
                Map.of(
                    "title",
                    "Reviewable rental",
                    "description",
                    "A clearly described rental for an independent review test.",
                    "showExactLocation",
                    false)),
            owner.token,
            201);
    String id = listing.get("id").asText();
    ok(
        "POST",
        path + "/listings/" + id + "/publish",
        Map.of(
            "version",
            0,
            "propertyVersion",
            1,
            "unitVersion",
            0,
            "images",
            List.of(Map.of("id", image, "version", 0))),
        owner.token,
        200);
    return new ListingFixture(owner, org, property, unit, id, image);
  }
}
