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
class AgencyCrmTests {
    private static final String PASSWORD = "Property-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;


    private String crm(Portfolio p){return "/api/v1/organizations/"+p.organization+"/agency";}
    private JsonNode client(Portfolio p)throws Exception{return node(success("POST",crm(p)+"/clients",Map.of("requestId",UUID.randomUUID(),"details",Map.of("fullName","Prospective renter","email","client@example.com","countryCode","KE","city","Nairobi","currency","KES","requirements","Two bedrooms near public transport.","consentToStore",true)),p.account.token,200));}
    private JsonNode active(Portfolio p)throws Exception{var property=createProperty(p,propertyDetails("Agency property"));return node(success("PATCH",p.base()+"/"+property.get("id").asText()+"/status",status(0,"ACTIVE"),p.account.token,200));}
    @Test void crmRequiresCurrentAgencyMembershipAndConsent()throws Exception{
      var agency=portfolio("AGENCY");var other=portfolio("AGENCY");var landlord=portfolio("LANDLORD");
      var response=success("GET",crm(agency)+"/dashboard",null,agency.account.token,200);
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      success("GET",crm(agency)+"/dashboard",null,other.account.token,404);
      success("GET",crm(landlord)+"/dashboard",null,landlord.account.token,400);
      var client=client(agency);success("GET",crm(other)+"/clients/"+client.get("id").asText(),null,other.account.token,404);
      var details=new HashMap<>(json.convertValue(client.get("details"),Map.class));details.put("consentToStore",false);
      success("POST",crm(agency)+"/clients",Map.of("requestId",UUID.randomUUID(),"details",details),agency.account.token,400);
      assertThat(node(success("GET",crm(agency)+"/clients?q=renter",null,agency.account.token,200)).size()).isEqualTo(1);
    }
    @Test void propertyAndLeadAssignmentsScopeAgentReadsAndRevocationDoesNotRevive()throws Exception{
      var a=portfolio("AGENCY");var agent=account();var teammate=account();addMember(a,agent,"MEMBER");addMember(a,teammate,"MEMBER");var p=active(a);String pid=p.get("id").asText();
      assertThat(node(success("GET",a.base(),null,agent.token,200)).size()).isZero();success("GET",a.base()+"/"+pid,null,agent.token,404);
      var assignment=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID(),"propertyId",pid,"agentId",agent.id,"active",true,"message","Responsible for viewings and follow-ups."));
      var assigned=node(success("POST",crm(a)+"/assignments",assignment,a.account.token,200));
      assertThat(node(success("GET",a.base(),null,agent.token,200)).size()).isEqualTo(1);
      var c=client(a);var leadBody=Map.of("requestId",UUID.randomUUID(),"clientId",c.get("id").asText(),"propertyId",pid,"agentId",agent.id,"title","Riverside search");
      var l=node(success("POST",crm(a)+"/leads",leadBody,a.account.token,200));String path=crm(a)+"/leads/"+l.get("id").asText();
      assertThat(node(success("POST",crm(a)+"/leads",leadBody,a.account.token,200)).get("id")).isEqualTo(l.get("id"));
      success("GET",path,null,agent.token,200);success("GET",path,null,teammate.token,404);
      success("GET",crm(a)+"/clients/"+c.get("id").asText(),null,agent.token,200);
      success("POST",a.base()+"/"+pid+"/units",unitDetails("Forbidden"),agent.token,403);
      success("POST",path+"/stage",Map.of("requestId",UUID.randomUUID(),"version",0,"stage","WON","message","Cannot fabricate conversion"),agent.token,409);
      success("POST",path+"/stage",Map.of("requestId",UUID.randomUUID(),"version",0,"stage","QUALIFIED","message","Budget and needs confirmed"),agent.token,200);
      success("POST",path+"/stage",Map.of("requestId",UUID.randomUUID(),"version",1,"stage","VIEWING","viewingId",UUID.randomUUID(),"message","Fake viewing"),agent.token,400);
      assignment.put("requestId",UUID.randomUUID());assignment.put("active",false);assignment.put("version",assigned.get("version").asLong());success("POST",crm(a)+"/assignments",assignment,a.account.token,200);
      success("GET",path,null,agent.token,404);
      success("DELETE","/api/v1/organizations/"+a.organization+"/members/"+agent.id,null,a.account.token,204);addMember(a,agent,"MEMBER");success("GET",path,null,agent.token,404);
    }
    @Test void followUpsAreScopedVersionedAndReplaySafe()throws Exception{
      var a=portfolio("AGENCY");var agent=account();addMember(a,agent,"MEMBER");var c=client(a);
      var l=node(success("POST",crm(a)+"/leads",Map.of("requestId",UUID.randomUUID(),"clientId",c.get("id").asText(),"agentId",agent.id,"title","New search"),a.account.token,200));
      var body=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID(),"leadId",l.get("id").asText(),"assigneeId",agent.id,"title","Call prospective renter","kind","CALL","dueAt",java.time.Instant.now().minusSeconds(300).toString(),"description","Discuss budget and location."));
      var t=node(success("POST",crm(a)+"/tasks",body,agent.token,200));String path=crm(a)+"/tasks/"+t.get("id").asText();
      assertThat(node(success("GET",crm(a)+"/dashboard",null,agent.token,200)).get("overdue").asLong()).isEqualTo(1);
      assertThat(node(success("GET",crm(a)+"/tasks?due=OVERDUE",null,agent.token,200)).size()).isEqualTo(1);
      body.put("title","Different payload");success("POST",crm(a)+"/tasks",body,agent.token,409);
      var done=Map.of("requestId",UUID.randomUUID(),"version",0,"message","Discussed preferences.");
      success("POST",path+"/complete",done,agent.token,200);success("POST",path+"/complete",done,agent.token,200);
      success("POST",path+"/cancel",Map.of("requestId",UUID.randomUUID(),"version",0,"message","Stale mutation"),agent.token,409);
      assertThat(node(success("GET",crm(a)+"/history/TASK/"+t.get("id").asText(),null,agent.token,200)).size()).isEqualTo(2);
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
