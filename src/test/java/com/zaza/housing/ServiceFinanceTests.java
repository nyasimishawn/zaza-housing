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
    properties = "zaza.services.commission-bps=250")
@ActiveProfiles("test")
class ServiceFinanceTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired FinanceTestClock clock;
  private static final java.nio.file.Path IMAGE_ROOT =
      java.nio.file.Path.of(".work", "work-evidence-tests-" + UUID.randomUUID()).toAbsolutePath();

  @org.springframework.test.context.DynamicPropertySource
  static void images(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("zaza.images.directory", () -> IMAGE_ROOT.toString());
  }

  @BeforeEach
  void resetClock() {
    clock.now = Instant.parse("2026-10-15T12:00:00Z");
  }

  @Test
  void openingRequiresTheCurrentAcceptedQuoteAndPayingAuthority() throws Exception {
    var c = accepted();
    String q = quote(c);
    var body =
        Map.of("requestId", UUID.randomUUID(), "workVersion", view(c).get("version").asLong());
    call("POST", c.manager() + "/payments", body, c.i.owner, 409);
    call("POST", c.tenant() + "/payments", body, c.i.tenant, 403);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    call("POST", c.manager() + "/payments", body, c.i.owner, 409);
    var f = finance(c);
    assertThat(statement(f).get("amount").decimalValue()).isEqualByComparingTo("1500");
    assertThat(statement(f).get("fee").decimalValue()).isEqualByComparingTo("37.50");
    call("GET", f.base(), null, c.i.tenant, 404);
    call("GET", f.base() + "/receipts", null, c.p.actor, 403);
    assertThat(statement(f).toString())
        .doesNotContain("tenantId", "propertyId", "payerUserId", "address");
  }

  @Test
  void personalServicePaymentsBelongToTheCustomerAndNotTheirLandlord() throws Exception {
    var c = booking(provider());
    call("POST", c.job() + "/accept", cmd(c), c.p.actor, 200);
    String q = quote(c);
    call("POST", c.tenant() + "/quotes/" + q + "/accept", consent(c), c.i.tenant, 200);
    var f = finance(c);
    assertThat(statement(f).get("payerType").asText()).isEqualTo("CUSTOMER");
    call("GET", f.base(), null, c.i.owner, 404);
    assertThat(node(call("GET", "/api/v1/me/service-payments", null, c.i.tenant, 200)).toString())
        .contains(f.id);
    call("POST", f.base() + "/claims", payment(f, "1500", false), c.i.owner, 404);
  }

  @Test
  void reportedFundsRemainUnconfirmedUntilAnIndependentReview() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    call("POST", f.base() + "/claims", payment(f, "700", false), f.payer(), 200);
    assertMoney(f, "received", "0");
    assertMoney(f, "due", "1500");
    String claim = first(f, "claims");
    call("POST", f.admin() + "/claims/" + claim + "/verify", confirm(f), f.payer(), 403);
    call("POST", f.admin() + "/claims/" + claim + "/verify", confirm(f), admin, 200);
    assertMoney(f, "received", "700");
    assertMoney(f, "due", "800");
    call("POST", f.admin() + "/claims/" + claim + "/reject", confirm(f), admin, 409);
    call("POST", f.base() + "/claims", payment(f, "800", false), f.payer(), 200);
    claim = first(f, "claims");
    call("POST", f.admin() + "/claims/" + claim + "/reject", confirm(f), admin, 200);
    assertMoney(f, "received", "700");
  }

  @Test
  void receiptVerificationRequiresAnIndependentOperatorAndCorrectPassword() throws Exception {
    var f = finance(scheduled());
    var body = payment(f, "1500", true);
    Account admin = operator();
    body.put("password", "Wrong-password-for-confirmation");
    call("POST", f.admin() + "/receipts", body, admin, 401);
    assertMoney(f, "received", "0");
    body.put("password", PASSWORD);
    promote(f.c.p.actor);
    call("POST", f.admin() + "/receipts", body, f.c.p.actor, 403);
    promote(f.payer());
    call("POST", f.admin() + "/receipts", body, f.payer(), 403);
    call("POST", f.admin() + "/receipts", body, admin, 200);
    assertMoney(f, "received", "1500");
  }

  @Test
  void financialRetriesAreBoundToActorPayloadAndAction() throws Exception {
    var f = finance(scheduled());
    Account admin = operator(), other = operator();
    var body = payment(f, "1500", true);
    call("POST", f.admin() + "/receipts", body, admin, 200);
    call("POST", f.admin() + "/receipts", body, admin, 200);
    assertMoney(f, "received", "1500");
    call("POST", f.admin() + "/receipts", body, other, 409);
    body.put("amount", "1501");
    call("POST", f.admin() + "/receipts", body, admin, 409);
    body.put("amount", "1500");
    body.put("password", "Wrong-password-for-confirmation");
    call("POST", f.admin() + "/receipts", body, admin, 401);
  }

  @Test
  void amountsDatesAndNormalizedTransferReferencesAreValidated() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    var b = payment(f, "1.001", true);
    call("POST", f.admin() + "/receipts", b, admin, 400);
    b.put("amount", "0");
    call("POST", f.admin() + "/receipts", b, admin, 400);
    b.put("amount", "100");
    b.put("receivedOn", today().plusDays(1).toString());
    call("POST", f.admin() + "/receipts", b, admin, 400);
    b.put("receivedOn", today().toString());
    String reference = "test-" + UUID.randomUUID();
    b.put("reference", reference);
    call("POST", f.admin() + "/receipts", b, admin, 200);
    b = payment(f, "100", true);
    b.put("reference", reference.toUpperCase(Locale.ROOT));
    call("POST", f.admin() + "/receipts", b, admin, 409);
    assertMoney(f, "received", "100");
  }

  @Test
  void partialRefundsCannotExceedTheirReceiptOrAccountAndRespectTransferDates() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    record(f, admin, "1500");
    String receipt = first(f, "receipts");
    var b = payment(f, "1600", true);
    call("POST", f.admin() + "/receipts/" + receipt + "/refunds", b, admin, 409);
    b = payment(f, "500", true);
    b.put("receivedOn", today().minusDays(1).toString());
    call("POST", f.admin() + "/receipts/" + receipt + "/refunds", b, admin, 400);
    b.put("receivedOn", today().toString());
    call("POST", f.admin() + "/receipts/" + receipt + "/refunds", b, admin, 200);
    assertMoney(f, "refunded", "500");
    assertMoney(f, "due", "500");
    assertMoney(f, "refundAvailable", "1000");
    call("POST", f.admin() + "/receipts/" + receipt + "/void", confirm(f), admin, 409);
  }

  @Test
  void voidedReceiptsKeepTheirReferenceAndAppendOnlyEvidence() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    var b = payment(f, "1500", true);
    String ref = b.get("reference").toString();
    call("POST", f.admin() + "/receipts", b, admin, 200);
    String receipt = first(f, "receipts");
    call("POST", f.admin() + "/receipts/" + receipt + "/void", confirm(f), admin, 200);
    assertMoney(f, "received", "0");
    b = payment(f, "1500", true);
    b.put("reference", ref);
    call("POST", f.admin() + "/receipts", b, admin, 409);
    assertThat(
            node(call("GET", f.base() + "/receipts", null, f.payer(), 200))
                .get(0)
                .get("voided")
                .asBoolean())
        .isTrue();
  }

  @Test
  void openFinancialAccountsPreventQuoteReplacementAndAbandonedFunds() throws Exception {
    var c = accepted();
    String quote = quote(c);
    call("POST", c.manager() + "/quotes/" + quote + "/accept", consent(c), c.i.owner, 200);
    var f = finance(c);
    Account admin = operator();
    record(f, admin, "1500");
    call("POST", c.job() + "/release", cmd(c), c.p.actor, 409);
    call("POST", c.manager() + "/cancel", cmd(c), c.i.owner, 409);
    var b = cmd(c);
    b.remove("message");
    b.putAll(
        Map.of(
            "amount",
            "1600",
            "currency",
            "KES",
            "scope",
            "A changed quote requires a fresh paying party approval.",
            "validUntil",
            clock.instant().plusSeconds(3600).toString()));
    call("POST", c.job() + "/quotes", b, c.p.actor, 409);
    call("POST", f.base() + "/close", financeCommand(f), f.payer(), 409);
  }

  @Test
  void refundedClosedAccountsAllowReleaseAndPreserveRestrictedFinancialHistory() throws Exception {
    var c = accepted();
    String q = quote(c);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    var f = finance(c);
    Account admin = operator();
    record(f, admin, "1500");
    call(
        "POST",
        f.admin() + "/receipts/" + first(f, "receipts") + "/refunds",
        payment(f, "1500", true),
        admin,
        200);
    var b = financeCommand(f);
    call("POST", f.base() + "/close", b, f.payer(), 200);
    call("POST", f.base() + "/close", b, f.payer(), 200);
    call("POST", c.job() + "/release", cmd(c), c.p.actor, 200);
    call("GET", c.job(), null, c.p.actor, 404);
    call("GET", f.base(), null, c.p.actor, 200);
    call("GET", f.base() + "/receipts", null, c.p.actor, 403);
    call("POST", f.base() + "/claims", payment(f, "100", false), f.payer(), 409);
  }

  @Test
  void completionFundingAndTheFullDisputePeriodAreRequiredForPayouts() throws Exception {
    var c = completed();
    var f = finance(c);
    Account admin = operator();
    String destination = destination(c.p, admin, "KES");
    call("POST", f.base() + "/payouts", payoutBody(f, destination), c.p.actor, 409);
    record(f, admin, "1500");
    assertThat(statement(f).get("payoutBlocker").asText()).isEqualTo("DISPUTE_PERIOD");
    clock.days(14);
    loginAll(c);
    login(admin);
    call("POST", f.base() + "/payouts", payoutBody(f, destination), c.p.actor, 409);
    clock.now = clock.instant().plusSeconds(1);
    assertMoney(f, "payoutAvailable", "1462.50");
    call("POST", f.base() + "/payouts", payoutBody(f, destination), c.p.actor, 200);
    assertMoney(f, "reserved", "1462.50");
  }

  @Test
  void successfulPayoutNeedsTwoOperatorsAndRecognizesTheFrozenCommission() throws Exception {
    var f = finance(completed());
    Account approver = operator(), verifier = operator();
    String p = readyPayout(f, approver);
    call("POST", f.admin() + "/payouts/" + p + "/approve", confirm(f), approver, 200);
    call("POST", f.admin() + "/payouts/" + p + "/paid", payment(f, "1462.50", true), approver, 403);
    login(verifier);
    call("POST", f.admin() + "/payouts/" + p + "/paid", payment(f, "1462.50", true), verifier, 200);
    assertMoney(f, "paid", "1462.50");
    assertMoney(f, "reserved", "0");
    assertMoney(f, "feesEarned", "37.50");
    assertMoney(f, "refundAvailable", "0");
    assertThat(statement(f).get("payoutBlocker").asText()).isEqualTo("ALREADY_PAID");
  }

  @Test
  void workDisputesAndReworkNeverReleaseFundsByThemselves() throws Exception {
    var c = completed();
    var f = finance(c);
    Account admin = operator();
    record(f, admin, "1500");
    call("POST", c.tenant() + "/disputes", cmd(c), c.i.tenant, 200);
    assertThat(statement(f).get("payoutBlocker").asText()).isEqualTo("WORK_DISPUTED");
    clock.days(15);
    loginAll(c);
    assertMoney(f, "payoutAvailable", "0");
    call("POST", c.manager() + "/rework", cmd(c), c.i.owner, 200);
    assertThat(statement(f).get("payoutBlocker").asText()).isEqualTo("COMPLETION_REQUIRED");
    assertMoney(f, "paid", "0");
  }

  @Test
  void payoutDestinationsRequireIndependentVerificationAndMatchingCurrency() throws Exception {
    var f = finance(completed());
    Account admin = operator();
    record(f, admin, "1500");
    clock.days(15);
    loginAll(f.c);
    login(admin);
    String dest = registerDestination(f.c.p, "KES");
    call("POST", f.base() + "/payouts", payoutBody(f, dest), f.c.p.actor, 409);
    promote(f.c.p.actor);
    call(
        "POST",
        "/api/v1/admin/service-finance/destinations/" + dest + "/review",
        destinationReview("VERIFIED"),
        f.c.p.actor,
        403);
    verifyDestination(dest, admin, "VERIFIED");
    String wrong = destination(f.c.p, admin, "USD");
    call("POST", f.base() + "/payouts", payoutBody(f, wrong), f.c.p.actor, 409);
    String foreign = destination(provider(), admin, "KES");
    call("POST", f.base() + "/payouts", payoutBody(f, foreign), f.c.p.actor, 404);
    call("POST", f.base() + "/payouts", payoutBody(f, dest), f.c.p.actor, 200);
  }

  @Test
  void revokedBeneficiariesBlockBothApprovalAndFinalTransferVerification() throws Exception {
    var f = finance(completed());
    Account admin = operator(), second = operator();
    String p = readyPayout(f, admin);
    String d = firstPayout(f).get("destinationId").asText();
    verifyDestination(d, admin, "REVOKED");
    call("POST", f.admin() + "/payouts/" + p + "/approve", confirm(f), admin, 409);
    verifyDestination(d, admin, "VERIFIED");
    call("POST", f.admin() + "/payouts/" + p + "/approve", confirm(f), admin, 200);
    verifyDestination(d, admin, "REVOKED");
    login(second);
    call("POST", f.admin() + "/payouts/" + p + "/paid", payment(f, "1462.50", true), second, 409);
    assertMoney(f, "paid", "0");
  }

  @Test
  void cancelledAndFailedPayoutsReleaseReservationsForAnExplicitRetry() throws Exception {
    var f = finance(completed());
    Account admin = operator();
    String p = readyPayout(f, admin), d = firstPayout(f).get("destinationId").asText();
    call("POST", f.base() + "/payouts/" + p + "/cancel", financeCommand(f), f.c.p.actor, 200);
    assertMoney(f, "reserved", "0");
    call("POST", f.base() + "/payouts", payoutBody(f, d), f.c.p.actor, 200);
    p = firstPayout(f).get("id").asText();
    call("POST", f.admin() + "/payouts/" + p + "/approve", confirm(f), admin, 200);
    call("POST", f.base() + "/payouts/" + p + "/cancel", financeCommand(f), f.c.p.actor, 409);
    call("POST", f.admin() + "/payouts/" + p + "/fail", confirm(f), admin, 200);
    assertMoney(f, "reserved", "0");
    assertMoney(f, "paid", "0");
    assertMoney(f, "payoutAvailable", "1462.50");
  }

  @Test
  void reservedPayoutsProtectTheirPrincipalAndCommissionFromRefundsAndVoids() throws Exception {
    var f = finance(completed());
    Account admin = operator();
    readyPayout(f, admin);
    String receipt = first(f, "receipts");
    call(
        "POST",
        f.admin() + "/receipts/" + receipt + "/refunds",
        payment(f, "0.01", true),
        admin,
        409);
    call("POST", f.admin() + "/receipts/" + receipt + "/void", confirm(f), admin, 409);
    assertMoney(f, "refundAvailable", "0");
  }

  @Test
  void overpaymentsRemainRefundableAfterTheProviderHasBeenPaid() throws Exception {
    var f = finance(completed());
    Account admin = operator(), second = operator();
    String p = readyPayout(f, admin);
    record(f, admin, "200");
    String excess = first(f, "receipts");
    call("POST", f.admin() + "/payouts/" + p + "/approve", confirm(f), admin, 200);
    login(second);
    call("POST", f.admin() + "/payouts/" + p + "/paid", payment(f, "1462.50", true), second, 200);
    assertMoney(f, "refundAvailable", "200");
    call(
        "POST",
        f.admin() + "/receipts/" + excess + "/refunds",
        payment(f, "200", true),
        second,
        200);
    assertMoney(f, "refundAvailable", "0");
    assertMoney(f, "paid", "1462.50");
  }

  @Test
  void concurrentReceiptEditsCannotReuseTheSameAccountVersion() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    var aBody = payment(f, "100", true);
    var bBody = payment(f, "200", true);
    var a =
        client.sendAsync(
            request("POST", f.admin() + "/receipts", aBody, admin),
            HttpResponse.BodyHandlers.ofString());
    var b =
        client.sendAsync(
            request("POST", f.admin() + "/receipts", bBody, admin),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(statement(f).get("received").decimalValue().intValue()).isIn(100, 200);
  }

  @Test
  void auditFailureRollsBackMoneyReferencesVersionAndIdempotencyTogether() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    String constraint = constraint();
    var b = payment(f, "1500", true);
    long version = statement(f).get("version").asLong();
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action <> 'SERVICE_FINANCE_PAYMENT_VERIFIED') NOT VALID");
    try {
      call("POST", f.admin() + "/receipts", b, admin, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertMoney(f, "received", "0");
    assertThat(statement(f).get("version").asLong()).isEqualTo(version);
    call("POST", f.admin() + "/receipts", b, admin, 200);
    assertMoney(f, "received", "1500");
  }

  @Test
  void financialNotificationsNeverExposeMaintenanceCostsToOccupantsOrAgencies() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    record(f, admin, "1500");
    var tenantInbox = node(call("GET", "/api/v1/me/notifications", null, f.c.i.tenant, 200));
    assertThat(tenantInbox.toString()).doesNotContain(f.id, "PAYMENT_VERIFIED", "1500.00");
    var providerInbox = node(call("GET", "/api/v1/me/notifications", null, f.c.p.actor, 200));
    assertThat(providerInbox.toString()).contains(f.id, "servicePaymentUrl");
    assertThat(providerInbox.toString()).doesNotContain(PASSWORD, "1500.00", "BANK_TRANSFER");
    var response = call("GET", f.base(), null, f.payer(), 200);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  }

  @Test
  void ordinaryProviderMembersAndUnrelatedUsersCannotReadFinancialRecords() throws Exception {
    var f = finance(scheduled());
    Account member = account(), stranger = account();
    call(
        "POST",
        "/api/v1/organizations/" + f.c.p.org + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        f.c.p.actor,
        201);
    call("GET", f.base(), null, member, 404);
    call("GET", f.base(), null, stranger, 404);
    call("GET", f.base(), null, null, 401);
    call(
        "GET",
        "/api/v1/organizations/" + f.c.p.org + "/provider-payouts/accounts",
        null,
        member,
        403);
    call(
        "GET",
        "/api/v1/organizations/" + f.c.p.org + "/provider-payouts/destinations",
        null,
        stranger,
        404);
  }

  @Test
  void financialSourceTermsAndEvidenceCannotBeRewrittenInTheDatabase() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    record(f, admin, "1500");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE service_payment_accounts SET amount=1 WHERE id=?",
                    UUID.fromString(f.id)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE service_payment_receipts SET amount=1 WHERE account_id=?",
                    UUID.fromString(f.id)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM service_finance_events WHERE account_id=?", UUID.fromString(f.id)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertMoney(f, "received", "1500");
    assertMoney(f, "amount", "1500");
  }

  @Test
  void summariesAreScopedAndGroupMoneyByCurrency() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    record(f, admin, "1500");
    String base = "/api/v1/organizations/" + f.c.p.org + "/provider-payouts";
    var summary = node(call("GET", base + "/summary", null, f.c.p.actor, 200));
    assertThat(summary.size()).isEqualTo(1);
    assertThat(summary.get(0).get("currency").asText()).isEqualTo("KES");
    assertThat(summary.get(0).get("received").decimalValue()).isEqualByComparingTo("1500");
    call("GET", base + "/accounts?limit=101", null, f.c.p.actor, 400);
    call("GET", f.base() + "/history?offset=-1", null, f.payer(), 400);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/organizations/" + f.c.i.organization + "/service-payments",
                    null,
                    f.payer(),
                    200))
                .toString())
        .contains(f.id);
    call("GET", "/api/v1/admin/service-finance/summary", null, f.payer(), 403);
  }

  @Test
  void finalTransfersMustMatchTheApprovedInstructionAndCannotBeRetriedAsNewMoney()
      throws Exception {
    var f = finance(completed());
    Account admin = operator(), second = operator();
    String p = readyPayout(f, admin);
    call("POST", f.admin() + "/payouts/" + p + "/approve", confirm(f), admin, 200);
    login(second);
    call("POST", f.admin() + "/payouts/" + p + "/paid", payment(f, "1500", true), second, 409);
    var b = payment(f, "1462.50", true);
    b.put("channel", "MOBILE_MONEY");
    call("POST", f.admin() + "/payouts/" + p + "/paid", b, second, 409);
    b.put("channel", "BANK_TRANSFER");
    call("POST", f.admin() + "/payouts/" + p + "/paid", b, second, 200);
    call("POST", f.admin() + "/payouts/" + p + "/paid", b, second, 200);
    call("POST", f.admin() + "/payouts/" + p + "/paid", payment(f, "1462.50", true), second, 409);
    assertMoney(f, "paid", "1462.50");
  }

  @Test
  void unresolvedIncomingClaimsMustBeReviewedBeforeTheAccountCanClose() throws Exception {
    var f = finance(scheduled());
    Account admin = operator();
    call("POST", f.base() + "/claims", payment(f, "1500", false), f.payer(), 200);
    call("POST", f.base() + "/close", financeCommand(f), f.payer(), 409);
    call("POST", f.admin() + "/claims/" + first(f, "claims") + "/reject", confirm(f), admin, 200);
    call("POST", f.base() + "/close", financeCommand(f), f.payer(), 200);
    assertThat(statement(f).get("status").asText()).isEqualTo("CLOSED");
  }

  @Test
  void concurrentPayoutRequestsReserveTheProviderEntitlementOnlyOnce() throws Exception {
    var f = finance(completed());
    Account admin = operator();
    record(f, admin, "1500");
    String d = destination(f.c.p, admin, "KES");
    clock.days(15);
    loginAll(f.c);
    var one = payoutBody(f, d);
    var two = payoutBody(f, d);
    var a =
        client.sendAsync(
            request("POST", f.base() + "/payouts", one, f.c.p.actor),
            HttpResponse.BodyHandlers.ofString());
    var b =
        client.sendAsync(
            request("POST", f.base() + "/payouts", two, f.c.p.actor),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertMoney(f, "reserved", "1462.50");
    assertThat(node(call("GET", f.base() + "/payouts", null, f.c.p.actor, 200)).size())
        .isEqualTo(1);
  }

  @Test
  void operatorAndProviderQueuesExposeLatestDecisionsWithoutDuplicatingMoney() throws Exception {
    var f = finance(completed());
    Account admin = operator();
    String p = readyPayout(f, admin);
    String provider = "/api/v1/organizations/" + f.c.p.org + "/provider-payouts/requests";
    assertThat(node(call("GET", provider + "?status=REQUESTED", null, f.c.p.actor, 200)).toString())
        .contains(p);
    call("POST", f.admin() + "/payouts/" + p + "/reject", confirm(f), admin, 200);
    var queue = node(call("GET", provider + "?status=REJECTED", null, f.c.p.actor, 200));
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.get(0).get("decisionReason").asText()).contains("external records");
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/admin/service-finance/payouts?status=REJECTED",
                    null,
                    admin,
                    200))
                .toString())
        .contains(p);
    call("GET", provider + "?status=UNKNOWN", null, f.c.p.actor, 400);
    call("GET", provider, null, account(), 404);
  }

  @Test
  void RevokedProviderAdministratorsImmediatelyLoseHistoricalFinancialAccess() throws Exception {
    var f = finance(scheduled());
    Account admin = account();
    String members = "/api/v1/organizations/" + f.c.p.org + "/members";
    call("POST", members, Map.of("email", admin.email, "role", "ADMIN"), f.c.p.actor, 201);
    call("GET", f.base(), null, admin, 200);
    call("PATCH", members + "/" + admin.id, Map.of("role", "MEMBER"), f.c.p.actor, 200);
    call("GET", f.base(), null, admin, 404);
  }

  @Test
  void commissionRoundsHalfUpOnceFromTheImmutableAcceptedQuote() throws Exception {
    var c = accepted();
    var q = cmd(c);
    q.remove("message");
    q.putAll(
        Map.of(
            "amount",
            "0.20",
            "currency",
            "KES",
            "validUntil",
            clock.instant().plusSeconds(3600).toString(),
            "scope",
            "A small fixed fee service quote to verify decimal rounding."));
    call("POST", c.job() + "/quotes", q, c.p.actor, 200);
    String quote = view(c).get("quoteId").asText();
    call("POST", c.manager() + "/quotes/" + quote + "/accept", consent(c), c.i.owner, 200);
    var f = finance(c);
    assertMoney(f, "fee", "0.01");
    assertMoney(f, "providerEntitlement", "0.19");
    var duplicate =
        Map.of("requestId", UUID.randomUUID(), "workVersion", view(c).get("version").asLong());
    call("POST", c.manager() + "/payments", duplicate, c.i.owner, 409);
  }

  private record Finance(Scenario c, String id, boolean personal) {
    Account payer() {
      return personal ? c.i.tenant : c.i.owner;
    }

    String base() {
      return "/api/v1/service-payments/" + id;
    }

    String admin() {
      return "/api/v1/admin/service-finance/accounts/" + id;
    }
  }

  private Finance finance(Scenario c) throws Exception {
    boolean personal = view(c).get("kind").asText().equals("SERVICE_BOOKING");
    var b = Map.of("requestId", UUID.randomUUID(), "workVersion", view(c).get("version").asLong());
    String id =
        node(call(
                "POST",
                (personal ? c.tenant() : c.manager()) + "/payments",
                b,
                personal ? c.i.tenant : c.i.owner,
                200))
            .get("id")
            .asText();
    return new Finance(c, id, personal);
  }

  private JsonNode statement(Finance f) throws Exception {
    return node(call("GET", f.base(), null, financialPayer(f), 200));
  }

  private Account financialPayer(Finance f) throws Exception {
    return f.payer();
  }

  private HashMap<String, Object> financeCommand(Finance f) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            statement(f).get("version").asLong(),
            "reason",
            "Reviewed independently against the external records."));
  }

  private HashMap<String, Object> confirm(Finance f) throws Exception {
    var b = financeCommand(f);
    b.put("password", PASSWORD);
    return b;
  }

  private HashMap<String, Object> payment(Finance f, String amount, boolean password)
      throws Exception {
    var b = financeCommand(f);
    b.putAll(
        Map.of(
            "amount",
            amount,
            "channel",
            "BANK_TRANSFER",
            "reference",
            "TEST-" + UUID.randomUUID(),
            "receivedOn",
            today().toString()));
    if (password) b.put("password", PASSWORD);
    return b;
  }

  private void record(Finance f, Account admin, String amount) throws Exception {
    call("POST", f.admin() + "/receipts", payment(f, amount, true), admin, 200);
  }

  private void assertMoney(Finance f, String key, String expected) throws Exception {
    assertThat(statement(f).get(key).decimalValue()).as(key).isEqualByComparingTo(expected);
  }

  private String first(Finance f, String resource) throws Exception {
    return node(call("GET", f.base() + "/" + resource, null, financialPayer(f), 200))
        .get(0)
        .get("id")
        .asText();
  }

  private Account operator() throws Exception {
    Account a = account();
    promote(a);
    return a;
  }

  private void promote(Account a) throws Exception {
    jdbc.update("UPDATE app_users SET platform_role='PLATFORM_ADMIN' WHERE id=?", a.id);
    login(a);
  }

  private String registerDestination(Provider p, String currency) throws Exception {
    return node(call(
            "POST",
            "/api/v1/organizations/" + p.org + "/provider-payouts/destinations",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "channel",
                "BANK_TRANSFER",
                "beneficiaryToken",
                "beneficiary_" + UUID.randomUUID(),
                "maskedLabel",
                "Test Bank ****1234",
                "currency",
                currency),
            p.actor,
            200))
        .get("id")
        .asText();
  }

  private Map<String, Object> destinationReview(String status) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "status",
        status,
        "password",
        PASSWORD,
        "reason",
        "Beneficiary identity and ownership checked independently with external records.");
  }

  private void verifyDestination(String id, Account admin, String status) throws Exception {
    call(
        "POST",
        "/api/v1/admin/service-finance/destinations/" + id + "/review",
        destinationReview(status),
        admin,
        200);
  }

  private String destination(Provider p, Account admin, String currency) throws Exception {
    String id = registerDestination(p, currency);
    verifyDestination(id, admin, "VERIFIED");
    return id;
  }

  private HashMap<String, Object> payoutBody(Finance f, String destination) throws Exception {
    var b = financeCommand(f);
    b.put("destinationId", destination);
    return b;
  }

  private JsonNode firstPayout(Finance f) throws Exception {
    return node(call("GET", f.base() + "/payouts", null, f.c.p.actor, 200)).get(0);
  }

  private String readyPayout(Finance f, Account admin) throws Exception {
    record(f, admin, "1500");
    String d = destination(f.c.p, admin, "KES");
    clock.days(15);
    loginAll(f.c);
    login(admin);
    call("POST", f.base() + "/payouts", payoutBody(f, d), f.c.p.actor, 200);
    return firstPayout(f).get("id").asText();
  }

  private Scenario maintenance() throws Exception {
    Inventory i = inventory();
    JsonNode lease = active(i);
    var body = createBody(lease, "MAINTENANCE", null);
    JsonNode w = node(call("POST", "/api/v1/me/work-requests", body, i.tenant, 200));
    return new Scenario(i, lease, null, w.get("id").asText());
  }

  private Scenario booking(Provider p) throws Exception {
    Inventory i = inventory();
    JsonNode lease = executed(i);
    JsonNode w =
        node(
            call(
                "POST",
                "/api/v1/me/work-requests",
                createBody(lease, "SERVICE_BOOKING", p),
                i.tenant,
                200));
    return new Scenario(i, lease, p, w.get("id").asText());
  }

  private Scenario assigned() throws Exception {
    Scenario c = maintenance();
    c.p = provider();
    triage(c);
    assign(c, c.p);
    return c;
  }

  private Scenario accepted() throws Exception {
    var c = assigned();
    call("POST", c.job() + "/accept", cmd(c), c.p.actor, 200);
    return c;
  }

  private Scenario scheduled() throws Exception {
    var c = accepted();
    String q = quote(c);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    acceptVisit(c);
    return c;
  }

  private Scenario started() throws Exception {
    var c = scheduled();
    clock.now = clock.instant().plusSeconds(3600);
    loginAll(c);
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 200);
    return c;
  }

  private Scenario completed() throws Exception {
    var c = started();
    submit(c);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200);
    return c;
  }

  private Provider provider() throws Exception {
    return provider("Nairobi", "PLUMBING");
  }

  private Provider provider(String city, String category) throws Exception {
    Account actor = account();
    String org = organization(actor, "SERVICE_PROVIDER");
    String base = "/api/v1/organizations/" + org + "/service-profile";
    call("POST", base, profile(city), actor, 200);
    JsonNode offer = node(call("POST", base + "/offers", offerDetails(category), actor, 200));
    String id = offer.get("id").asText();
    call(
        "POST",
        base + "/offers/" + id + "/state",
        Map.of("version", 0, "status", "ACTIVE"),
        actor,
        200);
    call("POST", base + "/state", Map.of("version", 0, "status", "ACTIVE"), actor, 200);
    return new Provider(actor, org, id);
  }

  private Map<String, Object> profile(String city) {
    return Map.of(
        "displayName",
        "Repairs team",
        "description",
        "Registered business service profile supplied for customers.",
        "areas",
        List.of(Map.of("countryCode", "KE", "city", city)));
  }

  private Map<String, Object> offerDetails(String category) {
    return Map.of(
        "category",
        category,
        "title",
        "Service inspection and repair",
        "description",
        "Inspection and repair scope to be agreed in a job quote.",
        "priceUnit",
        "QUOTE_REQUIRED",
        "currency",
        "KES");
  }

  private HashMap<String, Object> createBody(JsonNode lease, String kind, Provider provider) {
    var b = new HashMap<String, Object>();
    b.put("requestId", UUID.randomUUID());
    b.put("leaseId", lease.get("id").asText());
    b.put("kind", kind);
    b.put("category", "PLUMBING");
    b.put("priority", "NORMAL");
    b.put("title", "Leaking tap");
    b.put("description", "Kitchen tap has been leaking and needs a qualified inspection.");
    if (provider != null) {
      b.put("offerId", provider.offer);
      b.put("offerVersion", 1);
    }
    return b;
  }

  private JsonNode view(Scenario c) throws Exception {
    return node(call("GET", c.tenant(), null, c.i.tenant, 200));
  }

  private HashMap<String, Object> cmd(Scenario c) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            view(c).get("version").asLong(),
            "message",
            "Reviewed request details and confirmed this action."));
  }

  private HashMap<String, Object> consent(Scenario c) throws Exception {
    var b = cmd(c);
    b.put("consent", true);
    return b;
  }

  private void triage(Scenario c) throws Exception {
    var b = cmd(c);
    b.put("priority", "HIGH");
    call("POST", c.manager() + "/triage", b, c.i.owner, 200);
  }

  private void assign(Scenario c, Provider p) throws Exception {
    var b = cmd(c);
    b.put("offerId", p.offer);
    b.put("offerVersion", 1);
    call("POST", c.manager() + "/assign", b, c.i.owner, 200);
    c.p = p;
  }

  private String quote(Scenario c) throws Exception {
    var b = cmd(c);
    b.remove("message");
    b.put("amount", "1500.00");
    b.put("currency", "KES");
    b.put("validUntil", clock.instant().plus(Duration.ofDays(2)).toString());
    b.put("scope", "Inspect kitchen plumbing and replace the specified tap fitting.");
    call("POST", c.job() + "/quotes", b, c.p.actor, 200);
    return view(c).get("quoteId").asText();
  }

  private void propose(Scenario c, Instant start, Instant end) throws Exception {
    var b = cmd(c);
    b.remove("message");
    b.put("startAt", start.toString());
    b.put("endAt", end.toString());
    b.put("accessInstructions", "Ring the bell; the occupant will meet the technician.");
    call(
        "POST",
        c.p == null ? c.manager() + "/visits" : c.job() + "/visits",
        b,
        c.p == null ? c.i.owner : c.p.actor,
        200);
  }

  private void acceptVisit(Scenario c) throws Exception {
    call(
        "POST",
        c.tenant() + "/visits/" + view(c).get("visitId").asText() + "/accept",
        consent(c),
        c.i.tenant,
        200);
  }

  private void submit(Scenario c) throws Exception {
    var b = cmd(c);
    b.remove("message");
    b.put("summary", "Inspected the tap, replaced the fitting, and checked for leaks.");
    call(
        "POST",
        c.p == null ? c.manager() + "/submit-completion" : c.job() + "/submit-completion",
        b,
        c.p == null ? c.i.owner : c.p.actor,
        200);
  }

  private void loginAll(Scenario c) throws Exception {
    login(c.i.owner);
    login(c.i.tenant);
    if (c.p != null) login(c.p.actor);
  }

  private JsonNode rows(Scenario c, String resource, Account actor, String base) throws Exception {
    return node(call("GET", base + "/" + resource, null, actor, 200));
  }

  private byte[] png() throws Exception {
    var image = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
    try (var out = new java.io.ByteArrayOutputStream()) {
      javax.imageio.ImageIO.write(image, "png", out);
      image.flush();
      return out.toByteArray();
    }
  }

  private HttpResponse<String> upload(
      Scenario c,
      String base,
      Account actor,
      String visibility,
      UUID key,
      long version,
      byte[] image)
      throws Exception {
    String boundary = "work" + UUID.randomUUID().toString().replace("-", "");
    var out = new java.io.ByteArrayOutputStream();
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\";"
                + " filename=\"../../private.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(image);
    for (var e :
        Map.of(
                "requestId",
                key.toString(),
                "version",
                String.valueOf(version),
                "visibility",
                visibility,
                "caption",
                "Private repair photograph")
            .entrySet())
      out.write(
          ("\r\n--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + e.getKey()
                  + "\"\r\n\r\n"
                  + e.getValue())
              .getBytes(StandardCharsets.UTF_8));
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + base + "/evidence"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + actor.token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private long fileCount() throws Exception {
    try (var files = java.nio.file.Files.list(IMAGE_ROOT)) {
      return files.count();
    }
  }

  private static class Scenario {
    final Inventory i;
    final JsonNode lease;
    Provider p;
    final String id;

    Scenario(Inventory i, JsonNode lease, Provider p, String id) {
      this.i = i;
      this.lease = lease;
      this.p = p;
      this.id = id;
    }

    String tenant() {
      return "/api/v1/me/work-requests/" + id;
    }

    String manager() {
      return i.propertyPath() + "/work-requests/" + id;
    }

    String job() {
      return "/api/v1/organizations/" + p.org + "/service-jobs/" + id;
    }
  }

  private record Provider(Account actor, String org, String offer) {
    String profile() {
      return "/api/v1/organizations/" + org + "/service-profile";
    }

    String directory() {
      return "/api/v1/service-providers/" + org;
    }
  }

  private JsonNode draft(Inventory inventory) throws Exception {
    return draft(inventory, terms());
  }

  private JsonNode draft(Inventory inventory, Map<String, Object> terms) throws Exception {
    return node(
        call(
            "POST",
            inventory.base(),
            Map.of("applicationId", inventory.application, "applicationVersion", 3, "terms", terms),
            inventory.owner,
            201));
  }

  private JsonNode send(Inventory inventory, JsonNode lease) throws Exception {
    return node(
        call("POST", inventory.path(lease) + "/send", version(lease), inventory.owner, 200));
  }

  private JsonNode sent(Inventory inventory) throws Exception {
    return send(inventory, draft(inventory));
  }

  private JsonNode executed(Inventory inventory) throws Exception {
    JsonNode lease = sent(inventory);
    lease = sign(inventory, lease, true);
    return sign(inventory, lease, false);
  }

  private JsonNode active(Inventory inventory) throws Exception {
    JsonNode lease = executed(inventory);
    return node(
        call(
            "POST",
            inventory.path(lease) + "/activate",
            Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
            inventory.owner,
            200));
  }

  private JsonNode sign(Inventory inventory, JsonNode lease, boolean tenant) throws Exception {
    return node(
        call(
            "POST",
            (tenant ? own(lease) : inventory.path(lease)) + "/sign",
            signBody(lease, tenant),
            tenant ? inventory.tenant : inventory.owner,
            200));
  }

  private JsonNode requestEnd(Inventory inventory, JsonNode lease, boolean tenant, LocalDate date)
      throws Exception {
    String path = tenant ? own(lease) : inventory.path(lease);
    var body = new HashMap<String, Object>();
    body.put("version", lease.get("version").asLong());
    body.put("proposedEndDate", date.toString());
    body.put("message", "Please end by mutual agreement.");
    JsonNode preview =
        node(
            call(
                "POST",
                path + "/terminations/preview",
                body,
                tenant ? inventory.tenant : inventory.owner,
                200));
    body.put(
        "evidence",
        evidence(
            lease,
            tenant,
            preview.get("documentHash").asText(),
            PASSWORD,
            tenant ? "Tenant Person" : "Lease Account",
            true));
    return node(
        call(
            "POST",
            path + "/terminations",
            body,
            tenant ? inventory.tenant : inventory.owner,
            201));
  }

  private Map<String, Object> acceptBody(JsonNode lease, boolean tenant) {
    return Map.of(
        "version",
        lease.get("version").asLong(),
        "evidence",
        evidence(
            lease,
            tenant,
            lease.get("termination").get("documentHash").asText(),
            PASSWORD,
            tenant ? "Tenant Person" : "Lease Account",
            true));
  }

  private Map<String, Object> signBody(JsonNode lease, boolean tenant) {
    return Map.of(
        "version",
        lease.get("version").asLong(),
        "revisionId",
        revision(lease),
        "evidence",
        evidence(
            lease,
            tenant,
            hash(lease),
            PASSWORD,
            tenant ? "Tenant Person" : "Lease Account",
            true));
  }

  private Map<String, Object> evidence(
      JsonNode lease, boolean tenant, String hash, String password, String name, boolean consent) {
    return Map.of(
        "documentHash", hash, "typedName", name, "password", password, "consent", consent);
  }

  private Map<String, Object> version(JsonNode lease) {
    return Map.of("version", lease.get("version").asLong());
  }

  private Map<String, Object> reason(JsonNode lease, String text) {
    return Map.of("version", lease.get("version").asLong(), "message", text);
  }

  private Map<String, Object> terms() {
    return Map.of(
        "title",
        "Residential agreement",
        "startDate",
        today().toString(),
        "endDate",
        today().plusDays(30).toString(),
        "monthlyRent",
        "25000",
        "deposit",
        "50000",
        "currency",
        "KES",
        "rentDueDay",
        5,
        "agreementText",
        "Negotiated agreement for caf\u00e9 premises.\n"
            + "Both parties must review this supplied agreement text.");
  }

  private String own(JsonNode lease) {
    return "/api/v1/me/leases/" + lease.get("id").asText();
  }

  private String revision(JsonNode lease) {
    return lease.get("revision").get("id").asText();
  }

  private String hash(JsonNode lease) {
    return lease.get("revision").get("documentHash").asText();
  }

  private UUID id(JsonNode node) {
    return UUID.fromString(node.get("id").asText());
  }

  private String constraint() {
    return "lease_test_" + UUID.randomUUID().toString().replace("-", "");
  }

  private JsonNode manager(Inventory inventory, JsonNode lease) throws Exception {
    return node(call("GET", inventory.path(lease), null, inventory.owner, 200));
  }

  private JsonNode tenant(Inventory inventory, JsonNode lease) throws Exception {
    return node(call("GET", own(lease), null, inventory.tenant, 200));
  }

  private JsonNode unit(Inventory inventory) throws Exception {
    return node(call("GET", inventory.unitPath(), null, inventory.owner, 200));
  }

  private JsonNode app(Inventory inventory) throws Exception {
    return node(
        call(
            "GET",
            inventory.propertyPath() + "/applications/" + inventory.application,
            null,
            inventory.owner,
            200));
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
  static class FinanceClockConfiguration {
    @Bean
    @Primary
    FinanceTestClock leaseClock() {
      return new FinanceTestClock();
    }
  }

  static class FinanceTestClock extends Clock {
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
