package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentManagementTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired PaymentTestClock clock;

  @BeforeEach
  void resetClock() {
    clock.now = Instant.parse("2026-10-01T12:00:00Z");
  }

  @Test
  void previewUsesSignedTermsAndDoesNotCreateBilling() throws Exception {
    Inventory i = inventory();
    JsonNode lease = executed(i);
    JsonNode preview = node(call("GET", i.path(lease) + "/billing/preview", null, i.owner, 200));
    assertThat(preview.get("rentTotal").decimalValue()).isEqualByComparingTo("25000");
    assertThat(preview.get("deposit").decimalValue()).isEqualByComparingTo("50000");
    assertThat(preview.get("rent").size()).isEqualTo(1);
    assertThat(preview.get("endExclusive").asText()).isEqualTo("2026-11-01");
    call("GET", own(lease) + "/billing", null, i.tenant, 404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM billing_accounts WHERE lease_id=?", Integer.class, id(lease)))
        .isZero();
  }

  @Test
  void unsignedAndUnsharedLeasesCannotBeBilled() throws Exception {
    Inventory i = inventory();
    JsonNode lease = draft(i);
    call("GET", own(lease) + "/billing/preview", null, i.tenant, 404);
    call("POST", i.path(lease) + "/billing", openBody(lease), i.owner, 409);
    lease = send(i, lease);
    call("POST", i.path(lease) + "/billing", openBody(lease), i.owner, 409);
  }

  @Test
  void openingIsIdempotentAndPreservesImmutableInvoices() throws Exception {
    Inventory i = inventory();
    JsonNode lease = executed(i);
    var request = openBody(lease);
    JsonNode first = node(call("POST", i.path(lease) + "/billing", request, i.owner, 200));
    call("POST", i.path(lease) + "/billing", request, i.owner, 200);
    assertMoney(first, "outstanding", "75000");
    assertThat(first.get("version").asInt()).isZero();
    assertThat(rows(i, lease, "invoices").size()).isEqualTo(2);
    UUID invoice = id(rows(i, lease, "invoices").get(0));
    assertThatThrownBy(
            () -> jdbc.update("UPDATE billing_invoices SET amount=1 WHERE id=?", invoice))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM billing_invoices WHERE id=?", invoice))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM billing_events WHERE lease_id=?", Integer.class, id(lease)))
        .isEqualTo(1);
    call("POST", i.path(lease) + "/billing", openBody(lease), i.owner, 409);
  }

  @Test
  void openingRequiresCurrentLeaseVersionAndSupportedCurrency() throws Exception {
    Inventory i = inventory();
    JsonNode lease = executed(i);
    var body = new HashMap<>(openBody(lease));
    body.put("leaseVersion", 0);
    call("POST", i.path(lease) + "/billing", body, i.owner, 409);
    var terms = new HashMap<>(terms());
    terms.put("currency", "JPY");
    Inventory second = inventory();
    JsonNode jpy = draft(second, terms);
    jpy = send(second, jpy);
    jpy = sign(second, jpy, true);
    jpy = sign(second, jpy, false);
    call("POST", second.path(jpy) + "/billing", openBody(jpy), second.owner, 400);
  }

  @Test
  void paymentReportsNeverSettleInvoicesWithoutOwnerVerification() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    JsonNode account =
        node(
            call(
                "POST",
                own(lease) + "/billing/claims",
                reportBody(i, lease, "25000"),
                i.tenant,
                200));
    assertMoney(account, "received", "0");
    assertMoney(account, "outstanding", "75000");
    assertThat(rows(i, lease, "claims").get(0).get("status").asText()).isEqualTo("PENDING");
    assertThat(rows(i, lease, "payments").size()).isZero();
    call(
        "POST",
        i.path(lease) + "/activate",
        Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
        i.owner,
        409);
  }

  @Test
  void verificationNeedsPasswordAndCanOnlyResolveClaimOnce() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    call("POST", own(lease) + "/billing/claims", reportBody(i, lease, "25000"), i.tenant, 200);
    String claim = rows(i, lease, "claims").get(0).get("id").asText();
    var request = command(i, lease);
    request.put("password", "Wrong-password-42");
    call("POST", i.path(lease) + "/billing/claims/" + claim + "/verify", request, i.owner, 401);
    assertThat(rows(i, lease, "payments").size()).isZero();
    request.put("password", PASSWORD);
    call("POST", i.path(lease) + "/billing/claims/" + claim + "/verify", request, i.owner, 200);
    call("POST", i.path(lease) + "/billing/claims/" + claim + "/verify", request, i.owner, 200);
    assertThat(rows(i, lease, "payments").size()).isEqualTo(1);
    var another = command(i, lease);
    another.put("password", PASSWORD);
    call("POST", i.path(lease) + "/billing/claims/" + claim + "/verify", another, i.owner, 409);
    assertThat(account(i, lease).toString()).doesNotContain("password");
  }

  @Test
  void rejectionRetainsReportAndCreatesNoFunds() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    call("POST", own(lease) + "/billing/claims", reportBody(i, lease, "25000"), i.tenant, 200);
    String claim = rows(i, lease, "claims").get(0).get("id").asText();
    var request = command(i, lease);
    request.put("password", PASSWORD);
    call("POST", i.path(lease) + "/billing/claims/" + claim + "/reject", request, i.owner, 200);
    assertThat(rows(i, lease, "claims").get(0).get("status").asText()).isEqualTo("REJECTED");
    assertMoney(account(i, lease), "received", "0");
  }

  @Test
  void manuallyVerifiedReferencesAreUniqueEvenAcrossCaseChanges() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    var body = recordBody(i, lease, "500");
    body.put("reference", "Unique-Reference");
    call("POST", i.path(lease) + "/billing/payments", body, i.owner, 200);
    var duplicate = recordBody(i, lease, "500");
    duplicate.put("reference", "unique-reference");
    call("POST", i.path(lease) + "/billing/payments", duplicate, i.owner, 409);
    assertThat(rows(i, lease, "payments").size()).isEqualTo(1);
    assertMoney(account(i, lease), "unallocated", "500");
  }

  @Test
  void allocationsEnforceBothInvoiceAndPaymentBalances() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "30000"), rent = invoice(i, lease, "RENT");
    allocate(i, lease, p, rent, "10000", 200);
    assertMoney(account(i, lease), "rentCollected", "10000");
    assertMoney(account(i, lease), "unallocated", "20000");
    allocate(i, lease, p, rent, "16000", 409);
    allocate(i, lease, p, invoice(i, lease, "DEPOSIT"), "20001", 409);
    allocate(i, lease, p, rent, "15000", 200);
    assertMoney(account(i, lease), "outstanding", "50000");
  }

  @Test
  void staleWritersAndReusedRequestTargetsCannotDuplicateAllocations() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "75000");
    var request = allocationBody(i, lease, invoice(i, lease, "RENT"), "1000");
    call("POST", i.path(lease) + "/billing/payments/" + p + "/allocations", request, i.owner, 200);
    call("POST", i.path(lease) + "/billing/payments/" + p + "/allocations", request, i.owner, 200);
    var changed = new HashMap<>(request);
    changed.put("invoiceId", invoice(i, lease, "DEPOSIT"));
    call("POST", i.path(lease) + "/billing/payments/" + p + "/allocations", changed, i.owner, 409);
    changed = new HashMap<>(request);
    changed.put("requestId", UUID.randomUUID());
    call("POST", i.path(lease) + "/billing/payments/" + p + "/allocations", changed, i.owner, 409);
    assertThat(rows(i, lease, "allocations").size()).isEqualTo(1);
  }

  @Test
  void concurrentAllocationsHaveExactlyOneWinner() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "25000"),
        path = i.path(lease) + "/billing/payments/" + p + "/allocations";
    var first = allocationBody(i, lease, invoice(i, lease, "RENT"), "25000");
    var second = new HashMap<>(first);
    second.put("requestId", UUID.randomUUID());
    var a =
        client.sendAsync(
            request("POST", path, first, i.owner), HttpResponse.BodyHandlers.ofString());
    var b =
        client.sendAsync(
            request("POST", path, second, i.owner), HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertMoney(account(i, lease), "rentCollected", "25000");
    assertMoney(account(i, lease), "unallocated", "0");
  }

  @Test
  void allocationReversalAndReceiptVoidRetainEveryOriginalRow() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "25000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    var verify = command(i, lease);
    verify.put("password", PASSWORD);
    call("POST", i.path(lease) + "/billing/payments/" + p + "/void", verify, i.owner, 409);
    String allocation = rows(i, lease, "allocations").get(0).get("id").asText();
    call(
        "POST",
        i.path(lease) + "/billing/allocations/" + allocation + "/reverse",
        command(i, lease),
        i.owner,
        200);
    verify = command(i, lease);
    verify.put("password", PASSWORD);
    call("POST", i.path(lease) + "/billing/payments/" + p + "/void", verify, i.owner, 200);
    assertMoney(account(i, lease), "received", "0");
    assertMoney(account(i, lease), "outstanding", "75000");
    assertThat(rows(i, lease, "allocations").get(0).get("reversed").asBoolean()).isTrue();
    assertThat(rows(i, lease, "payments").get(0).get("voided").asBoolean()).isTrue();
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM payment_receipts WHERE id=?", UUID.fromString(p)))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM payment_allocation_reversals WHERE allocation_id=?",
                    UUID.fromString(allocation)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void creditNotesCannotConsumePaidMoneyOrChangeDepositObligation() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "10000"), rent = invoice(i, lease, "RENT");
    allocate(i, lease, p, rent, "10000", 200);
    var request = command(i, lease);
    request.put("amount", "15001");
    call("POST", i.path(lease) + "/billing/invoices/" + rent + "/credits", request, i.owner, 409);
    request.put("amount", "15000");
    call("POST", i.path(lease) + "/billing/invoices/" + rent + "/credits", request, i.owner, 200);
    request = command(i, lease);
    request.put("amount", "1");
    call(
        "POST",
        i.path(lease) + "/billing/invoices/" + invoice(i, lease, "DEPOSIT") + "/credits",
        request,
        i.owner,
        409);
    assertMoney(account(i, lease), "credited", "15000");
  }

  @Test
  void refundsRequireVerifiedUnallocatedMoneyAndCannotBeVoidedAfterward() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "30000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    var body = refundBody(i, lease, "5001");
    call("POST", i.path(lease) + "/billing/payments/" + p + "/refunds", body, i.owner, 409);
    body.put("amount", "5000");
    call("POST", i.path(lease) + "/billing/payments/" + p + "/refunds", body, i.owner, 200);
    call("POST", i.path(lease) + "/billing/payments/" + p + "/refunds", body, i.owner, 200);
    assertMoney(account(i, lease), "unallocated", "0");
    assertMoney(account(i, lease), "refunded", "5000");
    var verify = command(i, lease);
    verify.put("password", PASSWORD);
    call("POST", i.path(lease) + "/billing/payments/" + p + "/void", verify, i.owner, 409);
  }

  @Test
  void invalidAmountsPrecisionFutureDatesAndUnsupportedChannelsAreRejected() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    for (String amount : List.of("0", "-1", "0.001", "1000000000000"))
      call("POST", i.path(lease) + "/billing/payments", recordBody(i, lease, amount), i.owner, 400);
    var body = recordBody(i, lease, "100");
    body.put("receivedOn", today().plusDays(1).toString());
    call("POST", i.path(lease) + "/billing/payments", body, i.owner, 400);
    body.put("receivedOn", today().toString());
    body.put("channel", "FAKE_GATEWAY_SUCCESS");
    call("POST", i.path(lease) + "/billing/payments", body, i.owner, 400);
    assertThat(rows(i, lease, "payments").size()).isZero();
  }

  @Test
  void unrelatedTenantsMembersAndGuestsCannotSeeFinancialRecords() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    Account stranger = account(), member = account();
    call(
        "POST",
        "/api/v1/organizations/" + i.organization + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        i.owner,
        201);
    call("GET", own(lease) + "/billing", null, null, 401);
    call("GET", own(lease) + "/billing", null, stranger, 404);
    call("GET", i.path(lease) + "/billing", null, stranger, 404);
    call("GET", i.path(lease) + "/billing", null, member, 403);
    call("POST", i.path(lease) + "/billing/payments", recordBody(i, lease, "100"), i.tenant, 404);
  }

  @Test
  void delegatedAgenciesMayReadButCannotVerifyOrRefundOwnerFunds() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    Account agency = account();
    String organization = organization(agency, "AGENCY");
    call(
        "PATCH",
        i.propertyPath() + "/management",
        Map.of("version", 1, "managerOrganizationId", organization),
        i.owner,
        200);
    String path =
        "/api/v1/organizations/"
            + organization
            + "/properties/"
            + i.property
            + "/leases/"
            + id(lease)
            + "/billing";
    call("GET", path, null, agency, 200);
    call("POST", path + "/payments", recordBody(i, lease, "100"), agency, 403);
    call("POST", path + "/charges", chargeBody(i, lease, "100"), agency, 403);
    call("PATCH", i.propertyPath() + "/management", revokeManagement(2), i.owner, 200);
    call("GET", path, null, agency, 404);
  }

  @Test
  void receiptAndInvoiceIdsCannotBeUsedAcrossLeases() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "100");
    Inventory other = inventory();
    JsonNode foreign = billed(other);
    call("GET", own(foreign) + "/billing/payments/" + p + "/receipt", null, other.tenant, 404);
    allocate(i, lease, p, invoice(other, foreign, "RENT"), "100", 404);
    var request = allocationBody(other, foreign, invoice(other, foreign, "RENT"), "100");
    call(
        "POST",
        other.path(foreign) + "/billing/payments/" + p + "/allocations",
        request,
        other.owner,
        404);
  }

  @Test
  void privateReceiptsAndNotificationsExposeNoPasswords() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "100");
    var response =
        call(
            "GET",
            own(lease) + "/billing/payments/" + p + "/receipt/document",
            null,
            i.tenant,
            200);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
        .contains("attachment");
    assertThat(response.body()).contains("MANUALLY VERIFIED", "100.00").doesNotContain(PASSWORD);
    var receipt =
        node(call("GET", own(lease) + "/billing/payments/" + p + "/receipt", null, i.tenant, 200));
    assertThat(receipt.get("method").asText()).isEqualTo("MANUALLY_VERIFIED_EXTERNAL_PAYMENT");
    assertThat(node(call("GET", "/api/v1/me/notifications", null, i.tenant, 200)).toString())
        .contains("PAYMENT_RECORDED", "/billing")
        .doesNotContain(PASSWORD);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE entity_id=? AND changes::text LIKE ?",
                Integer.class,
                UUID.fromString(p),
                "%" + PASSWORD + "%"))
        .isZero();
  }

  @Test
  void overdueBalancesUseUtcDateAndExcludeFutureRent() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    assertMoney(account(i, lease), "overdue", "0");
    clock.days(5);
    login(i.owner);
    assertMoney(account(i, lease), "overdue", "75000");
    String p = record(i, lease, "25000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    assertMoney(account(i, lease), "overdue", "50000");
  }

  @Test
  void configuredMoveInRequiresDepositAndCurrentRentButLeaseVersionsRemainSeparate()
      throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "75000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    call(
        "POST",
        i.path(lease) + "/activate",
        Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
        i.owner,
        409);
    allocate(i, lease, p, invoice(i, lease, "DEPOSIT"), "50000", 200);
    call(
        "POST",
        i.path(lease) + "/activate",
        Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
        i.owner,
        200);
    assertMoney(account(i, lease), "rentCollected", "25000");
    assertMoney(account(i, lease), "depositHeld", "50000");
  }

  @Test
  void fullMoveOutRequiresAcceptedDeductionsRefundAndClearedFunds() throws Exception {
    Inventory i = inventory();
    JsonNode lease = paidClosed(i);
    call("POST", i.path(lease) + "/release-unit", releaseBody(lease), i.owner, 409);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    propose(i, lease, "5000");
    String proposal = rows(i, lease, "settlements").get(0).get("id").asText();
    call(
        "POST",
        i.path(lease) + "/billing/deposit/refunds",
        refundBody(i, lease, "45000"),
        i.owner,
        409);
    decide(i, lease, proposal, "ACCEPT");
    assertMoney(account(i, lease), "depositHeld", "45000");
    assertMoney(account(i, lease), "depositDeducted", "5000");
    call(
        "POST",
        i.path(lease) + "/billing/deposit/refunds",
        refundBody(i, lease, "45001"),
        i.owner,
        409);
    var body = refundBody(i, lease, "45000");
    call("POST", i.path(lease) + "/billing/deposit/refunds", body, i.owner, 200);
    call("POST", i.path(lease) + "/billing/deposit/refunds", body, i.owner, 200);
    assertMoney(account(i, lease), "depositHeld", "0");
    assertMoney(account(i, lease), "rentCollected", "25000");
    assertThat(account(i, lease).get("checkoutReady").asBoolean()).isTrue();
    call("POST", i.path(lease) + "/release-unit", releaseBody(lease), i.owner, 200);
    assertThat(unit(i).get("availability").asText()).isEqualTo("AVAILABLE");
  }

  @Test
  void disputedDepositCannotBeForcedAndReplacementNeedsNewTenantDecision() throws Exception {
    Inventory i = inventory();
    JsonNode lease = paidClosed(i);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    propose(i, lease, "5000");
    String proposal = rows(i, lease, "settlements").get(0).get("id").asText();
    decide(i, lease, proposal, "DISPUTE");
    call(
        "POST",
        i.path(lease) + "/billing/deposit/refunds",
        refundBody(i, lease, "45000"),
        i.owner,
        409);
    propose(i, lease, "1000");
    assertThat(rows(i, lease, "settlements").size()).isEqualTo(2);
    String replacement = rows(i, lease, "settlements").get(0).get("id").asText();
    decide(i, lease, replacement, "ACCEPT");
    assertMoney(account(i, lease), "depositDeducted", "1000");
    var request = command(i, lease);
    call(
        "POST",
        i.path(lease) + "/billing/deposit/proposals/" + replacement + "/cancel",
        request,
        i.owner,
        409);
  }

  @Test
  void pendingDepositProposalsCanBeWithdrawnButNotOverwritten() throws Exception {
    Inventory i = inventory();
    JsonNode lease = paidClosed(i);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    propose(i, lease, "0");
    String proposal = rows(i, lease, "settlements").get(0).get("id").asText();
    var body = command(i, lease);
    body.put("deduction", "10");
    call("POST", i.path(lease) + "/billing/deposit/proposals", body, i.owner, 409);
    call(
        "POST",
        i.path(lease) + "/billing/deposit/proposals/" + proposal + "/cancel",
        command(i, lease),
        i.owner,
        200);
    propose(i, lease, "0");
    assertThat(rows(i, lease, "settlements").get(1).get("status").asText()).isEqualTo("CANCEL");
  }

  @Test
  void finalizationRequiresClosedLeaseAndFreezesDepositAllocations() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 409);
    String p = record(i, lease, "75000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    allocate(i, lease, p, invoice(i, lease, "DEPOSIT"), "50000", 200);
    lease = close(i, lease);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    String depositAllocation = "";
    for (var a : rows(i, lease, "allocations"))
      if (a.get("invoiceId").asText().equals(invoice(i, lease, "DEPOSIT")))
        depositAllocation = a.get("id").asText();
    call(
        "POST",
        i.path(lease) + "/billing/allocations/" + depositAllocation + "/reverse",
        command(i, lease),
        i.owner,
        409);
    call("POST", i.path(lease) + "/billing/charges", chargeBody(i, lease, "100"), i.owner, 409);
  }

  @Test
  void unpaidDepositIsReleasedAtFinalizationWhileRentDebtRemains() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    lease = close(i, lease);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    assertMoney(account(i, lease), "credited", "50000");
    assertMoney(account(i, lease), "outstanding", "25000");
    assertMoney(account(i, lease), "depositHeld", "0");
    assertThat(account(i, lease).get("checkoutReady").asBoolean()).isFalse();
    String p = record(i, lease, "25000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    assertThat(account(i, lease).get("checkoutReady").asBoolean()).isTrue();
  }

  @Test
  void earlyTerminationCreditsUnearnedRentAndReleasesOverpaymentForRefund() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "25000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    // A signed lease may end by mutual consent before possession.
    lease = requestEnd(i, lease, true, today().plusDays(10));
    String ending = lease.get("termination").get("id").asText();
    lease =
        node(
            call(
                "POST",
                i.path(lease) + "/terminations/" + ending + "/accept",
                acceptBody(lease, false),
                i.owner,
                200));
    clock.days(10);
    login(i.owner);
    lease =
        node(
            call(
                "POST",
                i.path(lease) + "/complete",
                reason(lease, "Agreed early end."),
                i.owner,
                200));
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    assertMoney(account(i, lease), "rentCollected", "8064.52");
    assertMoney(account(i, lease), "unallocated", "16935.48");
    assertMoney(account(i, lease), "outstanding", "0");
    var body = refundBody(i, lease, "16935.48");
    call("POST", i.path(lease) + "/billing/payments/" + p + "/refunds", body, i.owner, 200);
    assertThat(account(i, lease).get("checkoutReady").asBoolean()).isTrue();
  }

  @Test
  void pendingPaymentClaimsBlockCheckoutEvenAfterBalancesAreZero() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String p = record(i, lease, "25000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    call("POST", own(lease) + "/billing/claims", reportBody(i, lease, "100"), i.tenant, 200);
    lease = close(i, lease);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    assertMoney(account(i, lease), "outstanding", "0");
    assertThat(account(i, lease).get("checkoutReady").asBoolean()).isFalse();
  }

  @Test
  void failedAuditRollsBackMoneyReferenceAndIdempotencyKey() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String constraint = constraint();
    var body = recordBody(i, lease, "100");
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK (action<>'BILLING_PAYMENT_RECORDED') NOT VALID");
    try {
      call("POST", i.path(lease) + "/billing/payments", body, i.owner, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(rows(i, lease, "payments").size()).isZero();
    assertThat(account(i, lease).get("version").asInt()).isZero();
    call("POST", i.path(lease) + "/billing/payments", body, i.owner, 200);
    assertMoney(account(i, lease), "received", "100");
  }

  @Test
  void failedNotificationRollsBackFinancialWrites() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    String constraint = constraint();
    var body = recordBody(i, lease, "100");
    jdbc.execute(
        "ALTER TABLE in_app_notifications ADD CONSTRAINT "
            + constraint
            + " CHECK (billing_event_id IS NULL) NOT VALID");
    try {
      call("POST", i.path(lease) + "/billing/payments", body, i.owner, 409);
    } finally {
      jdbc.execute("ALTER TABLE in_app_notifications DROP CONSTRAINT " + constraint);
    }
    assertThat(rows(i, lease, "payments").size()).isZero();
    call("POST", i.path(lease) + "/billing/payments", body, i.owner, 200);
  }

  @Test
  void paginatedHistoriesRemainPrivateAndRejectInvalidBounds() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    record(i, lease, "100");
    call("GET", own(lease) + "/billing/history?limit=0", null, i.tenant, 400);
    assertThat(
            node(call("GET", own(lease) + "/billing/history?limit=1&offset=1", null, i.tenant, 200))
                .size())
        .isEqualTo(1);
    assertThatThrownBy(() -> jdbc.execute("TRUNCATE billing_events"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void tenantAdministratorsCannotVerifyTheirOwnPaymentsAsLessor() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    call(
        "POST",
        "/api/v1/organizations/" + i.organization + "/members",
        Map.of("email", i.tenant.email, "role", "ADMIN"),
        i.owner,
        201);
    call("GET", i.path(lease) + "/billing", null, i.tenant, 200);
    call("POST", i.path(lease) + "/billing/payments", recordBody(i, lease, "100"), i.tenant, 403);
  }

  @Test
  void concurrentClaimVerificationCreatesOnlyOneReceipt() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    call("POST", own(lease) + "/billing/claims", reportBody(i, lease, "100"), i.tenant, 200);
    String claim = rows(i, lease, "claims").get(0).get("id").asText(),
        path = i.path(lease) + "/billing/claims/" + claim + "/verify";
    var first = command(i, lease);
    first.put("password", PASSWORD);
    var second = new HashMap<>(first);
    second.put("requestId", UUID.randomUUID());
    var a =
        client.sendAsync(
            request("POST", path, first, i.owner), HttpResponse.BodyHandlers.ofString());
    var b =
        client.sendAsync(
            request("POST", path, second, i.owner), HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(rows(i, lease, "payments").size()).isEqualTo(1);
    assertMoney(account(i, lease), "received", "100");
  }

  @Test
  void checkoutCreditsEveryFutureRentPeriodAfterEarlyTermination() throws Exception {
    Inventory i = inventory();
    var terms = new HashMap<>(terms());
    terms.put("endDate", today().plusMonths(3).toString());
    terms.put("deposit", "0");
    JsonNode lease = draft(i, terms);
    lease = send(i, lease);
    lease = sign(i, lease, true);
    lease = sign(i, lease, false);
    call("POST", i.path(lease) + "/billing", openBody(lease), i.owner, 200);
    String p = record(i, lease, "75000");
    for (var row : rows(i, lease, "invoices"))
      allocate(i, lease, p, row.get("id").asText(), "25000", 200);
    lease = requestEnd(i, lease, true, today().plusDays(10));
    String ending = lease.get("termination").get("id").asText();
    lease =
        node(
            call(
                "POST",
                i.path(lease) + "/terminations/" + ending + "/accept",
                acceptBody(lease, false),
                i.owner,
                200));
    clock.days(10);
    login(i.owner);
    lease =
        node(
            call(
                "POST",
                i.path(lease) + "/complete",
                reason(lease, "Mutual early closure."),
                i.owner,
                200));
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    assertMoney(account(i, lease), "outstanding", "0");
    assertMoney(account(i, lease), "rentCollected", "8064.52");
    assertMoney(account(i, lease), "unallocated", "66935.48");
    assertThat(rows(i, lease, "invoices").size()).isEqualTo(3);
  }

  @Test
  void addedChargesAreAuditedAndCanBePartiallyCredited() throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    var body = chargeBody(i, lease, "1500");
    call("POST", i.path(lease) + "/billing/charges", body, i.owner, 200);
    call("POST", i.path(lease) + "/billing/charges", body, i.owner, 200);
    String charge = invoice(i, lease, "CHARGE");
    var credit = command(i, lease);
    credit.put("amount", "500");
    call("POST", i.path(lease) + "/billing/invoices/" + charge + "/credits", credit, i.owner, 200);
    assertMoney(account(i, lease), "outstanding", "76000");
  }

  @Test
  void billingSourceTermsAndResolvedDepositDecisionsCannotBeRewritten() throws Exception {
    Inventory i = inventory();
    JsonNode lease = paidClosed(i);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    propose(i, lease, "5000");
    String settlement = rows(i, lease, "settlements").get(0).get("id").asText();
    decide(i, lease, settlement, "ACCEPT");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE billing_accounts SET currency='USD' WHERE lease_id=?", id(lease)))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE billing_accounts SET status='OPEN' WHERE lease_id=?", id(lease)))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE deposit_settlement_decisions SET decision='CANCEL' WHERE"
                        + " settlement_id=?",
                    UUID.fromString(settlement)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void dashboardsSeparateCurrenciesAndMatchLeaseBalances() throws Exception {
    Inventory first = inventory();
    JsonNode lease = billed(first);
    String p = record(first, lease, "60000");
    allocate(first, lease, p, invoice(first, lease, "DEPOSIT"), "50000", 200);
    Inventory second = inventory(first.owner, first.tenant, first.organization);
    var euroTerms = new HashMap<>(terms());
    euroTerms.put("currency", "EUR");
    euroTerms.put("monthlyRent", "100");
    euroTerms.put("deposit", "200");
    JsonNode euro = draft(second, euroTerms);
    euro = send(second, euro);
    euro = sign(second, euro, true);
    euro = sign(second, euro, false);
    call("POST", second.path(euro) + "/billing", openBody(euro), second.owner, 200);
    JsonNode tenantSummary =
        node(call("GET", "/api/v1/me/leases/billing-summary", null, first.tenant, 200));
    assertThat(tenantSummary.size()).isEqualTo(2);
    assertThat(tenantSummary.get(0).get("currency").asText()).isEqualTo("EUR");
    assertMoney(tenantSummary.get(0), "outstanding", "300");
    assertMoney(tenantSummary.get(1), "depositHeld", "50000");
    assertMoney(tenantSummary.get(1), "unallocated", "10000");
    JsonNode property =
        node(call("GET", first.base() + "/billing-summary", null, first.owner, 200));
    assertThat(property.size()).isEqualTo(1);
    assertMoney(property.get(0), "outstanding", "25000");
    Account other = account();
    assertThat(node(call("GET", "/api/v1/me/leases/billing-summary", null, other, 200)).size())
        .isZero();
    call("GET", first.base() + "/billing-summary", null, other, 404);
  }

  @Test
  void finalizationProcessesScheduledObligationsBeyondAThousandInspectionCharges()
      throws Exception {
    Inventory i = inventory();
    JsonNode lease = billed(i);
    jdbc.update(
        "INSERT INTO billing_invoices(id,lease_id,kind,description,due_date,amount) SELECT"
            + " gen_random_uuid(),?,'CHARGE','Historical inspection charge',DATE '2026-09-01',1"
            + " FROM generate_series(1,1100)",
        id(lease));
    lease = close(i, lease);
    call("POST", i.path(lease) + "/billing/finalize", command(i, lease), i.owner, 200);
    assertMoney(account(i, lease), "credited", "50000");
    assertMoney(account(i, lease), "outstanding", "26100");
  }

  private Map<String, Object> revokeManagement(int version) {
    var body = new HashMap<String, Object>();
    body.put("version", version);
    body.put("managerOrganizationId", null);
    return body;
  }

  private JsonNode billed(Inventory i) throws Exception {
    JsonNode lease = executed(i);
    call("POST", i.path(lease) + "/billing", openBody(lease), i.owner, 200);
    return lease;
  }

  private Map<String, Object> openBody(JsonNode lease) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "leaseVersion",
        lease.get("version").asLong(),
        "policy",
        "CALENDAR_ACTUAL_DAYS");
  }

  private JsonNode account(Inventory i, JsonNode lease) throws Exception {
    return node(call("GET", i.path(lease) + "/billing", null, i.owner, 200));
  }

  private JsonNode rows(Inventory i, JsonNode lease, String kind) throws Exception {
    return node(call("GET", i.path(lease) + "/billing/" + kind, null, i.owner, 200));
  }

  private String invoice(Inventory i, JsonNode lease, String kind) throws Exception {
    for (var r : rows(i, lease, "invoices"))
      if (r.get("kind").asText().equals(kind)) return r.get("id").asText();
    throw new AssertionError("Invoice missing");
  }

  private HashMap<String, Object> command(Inventory i, JsonNode lease) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            account(i, lease).get("version").asLong(),
            "reason",
            "Verified against external statement and inspection."));
  }

  private HashMap<String, Object> reportBody(Inventory i, JsonNode lease, String amount)
      throws Exception {
    var r = command(i, lease);
    r.put("amount", amount);
    r.put("channel", "BANK_TRANSFER");
    r.put("reference", UUID.randomUUID().toString());
    r.put("receivedOn", today().toString());
    return r;
  }

  private HashMap<String, Object> recordBody(Inventory i, JsonNode lease, String amount)
      throws Exception {
    var r = reportBody(i, lease, amount);
    r.put("password", PASSWORD);
    return r;
  }

  private HashMap<String, Object> refundBody(Inventory i, JsonNode lease, String amount)
      throws Exception {
    return recordBody(i, lease, amount);
  }

  private HashMap<String, Object> allocationBody(
      Inventory i, JsonNode lease, String invoice, String amount) throws Exception {
    var r = command(i, lease);
    r.remove("reason");
    r.put("invoiceId", invoice);
    r.put("amount", amount);
    return r;
  }

  private HashMap<String, Object> chargeBody(Inventory i, JsonNode lease, String amount)
      throws Exception {
    var r = command(i, lease);
    r.put("amount", amount);
    r.put("dueDate", today().toString());
    return r;
  }

  private String record(Inventory i, JsonNode lease, String amount) throws Exception {
    call("POST", i.path(lease) + "/billing/payments", recordBody(i, lease, amount), i.owner, 200);
    return rows(i, lease, "payments").get(0).get("id").asText();
  }

  private void allocate(
      Inventory i, JsonNode lease, String payment, String invoice, String amount, int status)
      throws Exception {
    call(
        "POST",
        i.path(lease) + "/billing/payments/" + payment + "/allocations",
        allocationBody(i, lease, invoice, amount),
        i.owner,
        status);
  }

  private void assertMoney(JsonNode node, String field, String value) {
    assertThat(node.get(field).decimalValue()).as(field).isEqualByComparingTo(value);
  }

  private JsonNode close(Inventory i, JsonNode lease) throws Exception {
    clock.days(31);
    login(i.owner);
    login(i.tenant);
    return node(
        call(
            "POST",
            i.path(lease) + "/complete",
            reason(lease, "Term has ended; move-out inspection completed."),
            i.owner,
            200));
  }

  private JsonNode paidClosed(Inventory i) throws Exception {
    JsonNode lease = billed(i);
    String p = record(i, lease, "75000");
    allocate(i, lease, p, invoice(i, lease, "RENT"), "25000", 200);
    allocate(i, lease, p, invoice(i, lease, "DEPOSIT"), "50000", 200);
    return close(i, lease);
  }

  private Map<String, Object> releaseBody(JsonNode lease) {
    return Map.of(
        "version",
        lease.get("version").asLong(),
        "vacancyConfirmed",
        true,
        "message",
        "Inspection confirms vacancy and settled finances.");
  }

  private void propose(Inventory i, JsonNode lease, String deduction) throws Exception {
    var body = command(i, lease);
    body.put("deduction", deduction);
    call("POST", i.path(lease) + "/billing/deposit/proposals", body, i.owner, 200);
  }

  private void decide(Inventory i, JsonNode lease, String proposal, String decision)
      throws Exception {
    var body = command(i, lease);
    body.put("decision", decision);
    call(
        "POST",
        own(lease) + "/billing/deposit/proposals/" + proposal + "/decision",
        body,
        i.tenant,
        200);
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
        today().plusMonths(1).toString(),
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
    return inventory(account(), account(), null);
  }

  private Inventory inventory(Account owner, Account tenant, String organization) throws Exception {
    if (organization == null) organization = organization(owner, "LANDLORD");
    if (jdbc.queryForObject(
            "SELECT count(*) FROM tenant_profiles WHERE user_id=?", Integer.class, tenant.id)
        == 0)
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
  static class TestClockConfiguration {
    @Bean
    @Primary
    PaymentTestClock leaseClock() {
      return new PaymentTestClock();
    }
  }

  static class PaymentTestClock extends Clock {
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
