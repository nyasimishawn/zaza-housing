package com.zaza.housing;

import com.zaza.housing.model.Property;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgencyMandateTests {
    private static final String PASSWORD = "Property-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;


    private String base(Portfolio p){return "/api/v1/organizations/"+p.organization+"/agency/mandates";}
    private Map<String,Object> command(long version){return Map.of("requestId",UUID.randomUUID(),"version",version,"message","Reviewed the agreed responsibilities and fee terms.","consent",true,"password",PASSWORD);}
    private Map<String,Object> offer(JsonNode property,Portfolio agency){return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"propertyId",property.get("id").asText(),"propertyVersion",property.get("version").asLong(),"agencyId",agency.organization,"responsibilities","Handle listing enquiries, viewings and tenant follow-ups.","feeTerms","Fixed fee agreed separately before any financial payment.","startsAt",java.time.Instant.now().minusSeconds(60).toString(),"endsAt",java.time.Instant.now().plusSeconds(86400).toString(),"consent",true,"password",PASSWORD));}
    @Test void ownerAndSeparateAgencyMustAgreeBeforeDelegationAndRevocationIsImmediate()throws Exception{
      var owner=portfolio("LANDLORD");var agency=portfolio("AGENCY");var outsider=portfolio("AGENCY");var p=createProperty(owner,propertyDetails("Mandated property"));String property=owner.base()+"/"+p.get("id").asText();p=node(success("PATCH",property+"/status",status(0,"ACTIVE"),owner.account.token,200));
      var body=offer(p,agency);var m=node(success("POST",base(owner),body,owner.account.token,200));String id=m.get("id").asText();
      success("GET",agency.base()+"/"+p.get("id").asText(),null,agency.account.token,404);
      success("POST",base(owner)+"/"+id+"/accept",command(0),owner.account.token,403);
      success("GET",base(outsider)+"/"+id,null,outsider.account.token,404);
      var accept=command(0);var accepted=node(success("POST",base(agency)+"/"+id+"/accept",accept,agency.account.token,200));assertThat(accepted.get("effective").asBoolean()).isTrue();
      success("POST",base(agency)+"/"+id+"/accept",accept,agency.account.token,200);
      success("GET",agency.base()+"/"+p.get("id").asText(),null,agency.account.token,200);
      success("POST",base(owner)+"/"+id+"/revoke",command(1),owner.account.token,200);
      success("GET",agency.base()+"/"+p.get("id").asText(),null,agency.account.token,404);
      assertThat(node(success("GET",base(agency)+"/"+id+"/history",null,agency.account.token,200)).size()).isEqualTo(3);
    }
    @Test void changedPropertyAndInvalidPasswordCannotAcceptOrMutateFrozenTerms()throws Exception{
      var owner=portfolio("LANDLORD");var agency=portfolio("AGENCY");var p=createProperty(owner,propertyDetails("Frozen property"));String property=owner.base()+"/"+p.get("id").asText();p=node(success("PATCH",property+"/status",status(0,"ACTIVE"),owner.account.token,200));
      var m=node(success("POST",base(owner),offer(p,agency),owner.account.token,200));String id=m.get("id").asText();
      var bad=new HashMap<>(command(0));bad.put("password","Incorrect-password-42");success("POST",base(agency)+"/"+id+"/accept",bad,agency.account.token,401);
      success("PUT",property,edit(1,propertyDetails("Changed property")),owner.account.token,200);
      success("POST",base(agency)+"/"+id+"/accept",command(0),agency.account.token,409);
      assertThatThrownBy(()->jdbc.update("UPDATE agency_mandates SET terms='{}'::jsonb WHERE id=?",UUID.fromString(id))).isInstanceOf(org.springframework.dao.DataAccessException.class);
      success("POST",base(owner)+"/"+id+"/cancel",command(0),owner.account.token,200);
    }
    @Test void futureMandateHasNoAuthorityBeforeStartAndCannotBeReplacedAsExpired()throws Exception{
      var owner=portfolio("LANDLORD");var agency=portfolio("AGENCY");var p=createProperty(owner,propertyDetails("Future property"));String property=owner.base()+"/"+p.get("id").asText();p=node(success("PATCH",property+"/status",status(0,"ACTIVE"),owner.account.token,200));var body=offer(p,agency);body.put("startsAt",java.time.Instant.now().plusSeconds(3600).toString());
      var m=node(success("POST",base(owner),body,owner.account.token,200));String id=m.get("id").asText();var accepted=node(success("POST",base(agency)+"/"+id+"/accept",command(0),agency.account.token,200));assertThat(accepted.get("effective").asBoolean()).isFalse();
      success("GET",agency.base()+"/"+p.get("id").asText(),null,agency.account.token,404);
      p=node(success("GET",property,null,owner.account.token,200));success("POST",base(owner),offer(p,agency),owner.account.token,409);
    }
    @Test void endingAnOldMandateCannotRemoveALaterManualDelegation()throws Exception{
      var owner=portfolio("LANDLORD");var agency=portfolio("AGENCY");var next=portfolio("AGENCY");var p=createProperty(owner,propertyDetails("Transferred property"));String property=owner.base()+"/"+p.get("id").asText();p=node(success("PATCH",property+"/status",status(0,"ACTIVE"),owner.account.token,200));
      var m=node(success("POST",base(owner),offer(p,agency),owner.account.token,200));String id=m.get("id").asText();success("POST",base(agency)+"/"+id+"/accept",command(0),agency.account.token,200);
      success("PATCH",property+"/management",management(2,next.organization),owner.account.token,200);
      success("POST",base(owner)+"/"+id+"/revoke",command(1),owner.account.token,200);
      var after=node(success("GET",property,null,owner.account.token,200));assertThat(after.get("managerOrganizationId").asText()).isEqualTo(next.organization);
      success("GET",next.base()+"/"+p.get("id").asText(),null,next.account.token,200);
    }
    @Test void expiryRemovesAuthorityWithoutAWorker()throws Exception{
      var owner=portfolio("LANDLORD");var agency=portfolio("AGENCY");var p=createProperty(owner,propertyDetails("Expiring property"));String property=owner.base()+"/"+p.get("id").asText();p=node(success("PATCH",property+"/status",status(0,"ACTIVE"),owner.account.token,200));
      var body=offer(p,agency);body.put("endsAt",java.time.Instant.now().plusSeconds(6).toString());var m=node(success("POST",base(owner),body,owner.account.token,200));String id=m.get("id").asText();success("POST",base(agency)+"/"+id+"/accept",command(0),agency.account.token,200);
      success("GET",agency.base()+"/"+p.get("id").asText(),null,agency.account.token,200);Thread.sleep(6500);
      success("GET",agency.base()+"/"+p.get("id").asText(),null,agency.account.token,404);
      assertThat(node(success("GET",base(agency)+"/"+id,null,agency.account.token,200)).get("effective").asBoolean()).isFalse();
    }
    private Portfolio portfolio(String type) throws Exception {
        Account actor = account();
        JsonNode organization = node(success("POST", "/api/v1/organizations", Map.of("name", "Property test organization", "type", type), actor.token, 201));
        return new Portfolio(actor, organization.get("id").asText());
    }

    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode registered = node(success("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Property test"), null, 201));
        JsonNode loggedIn = node(success("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200));
        return new Account(UUID.fromString(registered.get("id").asText()), email, loggedIn.get("accessToken").asText());
    }

    private void addMember(Portfolio portfolio, Account member, String role) throws Exception {
        success("POST", "/api/v1/organizations/" + portfolio.organization + "/members", Map.of("email", member.email, "role", role), portfolio.account.token, 201);
    }

    private JsonNode createProperty(Portfolio portfolio, Object details) throws Exception {
        return node(success("POST", portfolio.base(), details, portfolio.account.token, 201));
    }

    private Map<String, Object> propertyDetails(String name) {
        return new HashMap<>(Map.of("name", name, "description", "A property management test", "type", "HOUSE",
                "countryCode", "ke", "city", "Nairobi", "address", "Test street 1"));
    }

    private Map<String, Object> unitDetails(String label) {
        return new HashMap<>(Map.of("label", label, "bedrooms", 2, "bathrooms", 1, "floorAreaSqm", "72.50",
                "monthlyRent", "25000.50", "deposit", "50000.25", "currency", "kes", "availability", "AVAILABLE"));
    }

    private Map<String, Object> edit(long version, Object details) { return Map.of("version", version, "details", details); }
    private Map<String, Object> status(long version, String status) { return Map.of("version", version, "status", status); }
    private Map<String, Object> management(long version, String organization) {
        Map<String, Object> result = new HashMap<>(); result.put("version", version); result.put("managerOrganizationId", organization); return result;
    }

    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    private HttpResponse<String> success(String method, String path, Object body, String token, int status) throws Exception {
        var response = call(method, path, body, token);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return response;
    }

    private HttpRequest request(String method, String path, Object body, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        return client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> async(String method, String path, Object body, String token) {
        return client.sendAsync(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private record Account(UUID id, String email, String token) { }
    private record Portfolio(Account account, String organization) {
        String base() { return "/api/v1/organizations/" + organization + "/properties"; }
    }
}
