package com.zaza.housing.controller;

import com.zaza.housing.dto.AddChargeRequest;
import com.zaza.housing.dto.AllocatePaymentRequest;
import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingCommandRequest;
import com.zaza.housing.dto.BillingEntryResponse;
import com.zaza.housing.dto.BillingPreviewResponse;
import com.zaza.housing.dto.CreditInvoiceRequest;
import com.zaza.housing.dto.DepositSettlementResponse;
import com.zaza.housing.dto.InvoiceResponse;
import com.zaza.housing.dto.OpenBillingRequest;
import com.zaza.housing.dto.PaymentAllocationResponse;
import com.zaza.housing.dto.PaymentClaimResponse;
import com.zaza.housing.dto.PaymentReceiptResponse;
import com.zaza.housing.dto.PaymentResponse;
import com.zaza.housing.dto.ProposeDepositRequest;
import com.zaza.housing.dto.RecordPaymentRequest;
import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.dto.RefundResponse;
import com.zaza.housing.dto.VerifyPaymentRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.BillingReadService;
import com.zaza.housing.service.BillingService;
import com.zaza.housing.service.DepositService;
import com.zaza.housing.service.PaymentService;
import com.zaza.housing.util.AgreementHttpResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/properties/{propertyId}/leases/{id}/billing")
public class LeaseBillingController {
  private final BillingReadService reads;
  private final BillingService billing;
  private final PaymentService payments;
  private final DepositService deposits;

  public LeaseBillingController(
      BillingReadService reads,
      BillingService billing,
      PaymentService payments,
      DepositService deposits) {
    this.reads = reads;
    this.billing = billing;
    this.payments = payments;
    this.deposits = deposits;
  }

  @GetMapping
  public BillingAccountResponse account(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id) {
    return reads.account(actor, organizationId, propertyId, id);
  }

  @GetMapping("/preview")
  public BillingPreviewResponse preview(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id) {
    return billing.preview(actor, organizationId, propertyId, id);
  }

  @GetMapping("/invoices")
  public List<InvoiceResponse> invoices(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.invoices(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/payments")
  public List<PaymentResponse> payments(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.payments(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/claims")
  public List<PaymentClaimResponse> claims(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.claims(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/allocations")
  public List<PaymentAllocationResponse> allocations(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.allocations(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/refunds")
  public List<RefundResponse> refunds(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.refunds(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/settlements")
  public List<DepositSettlementResponse> settlements(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.settlements(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/history")
  public List<BillingEntryResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/payments/{paymentId}/receipt")
  public PaymentReceiptResponse receipt(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID paymentId) {
    return reads.receipt(actor, organizationId, propertyId, id, paymentId);
  }

  @GetMapping("/payments/{paymentId}/receipt/document")
  public ResponseEntity<String> receiptDocument(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID paymentId) {
    return AgreementHttpResponse.document(
        reads.receiptText(actor, organizationId, propertyId, id, paymentId),
        "receipt-" + paymentId + ".txt");
  }

  @PostMapping("")
  public BillingAccountResponse open(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody OpenBillingRequest request) {
    return billing.open(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/charges")
  public BillingAccountResponse charge(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody AddChargeRequest request) {
    return billing.charge(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/finalize")
  public BillingAccountResponse finalizeBilling(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody BillingCommandRequest request) {
    return billing.finalizeBilling(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/payments")
  public BillingAccountResponse record(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody RecordPaymentRequest request) {
    return payments.record(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/deposit/proposals")
  public BillingAccountResponse propose(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody ProposeDepositRequest request) {
    return deposits.propose(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/deposit/refunds")
  public BillingAccountResponse depositRefund(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody RecordRefundRequest request) {
    return deposits.refund(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/invoices/{invoiceId}/credits")
  public BillingAccountResponse credit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID invoiceId,
      @Valid @RequestBody CreditInvoiceRequest request) {
    return billing.credit(actor, organizationId, propertyId, id, invoiceId, request);
  }

  @PostMapping("/claims/{claimId}/verify")
  public BillingAccountResponse verify(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.verify(actor, organizationId, propertyId, id, claimId, request, true);
  }

  @PostMapping("/claims/{claimId}/reject")
  public BillingAccountResponse reject(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.verify(actor, organizationId, propertyId, id, claimId, request, false);
  }

  @PostMapping("/payments/{paymentId}/allocations")
  public BillingAccountResponse allocate(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID paymentId,
      @Valid @RequestBody AllocatePaymentRequest request) {
    return payments.allocate(actor, organizationId, propertyId, id, paymentId, request);
  }

  @PostMapping("/payments/{paymentId}/void")
  public BillingAccountResponse voidPayment(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID paymentId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.voidPayment(actor, organizationId, propertyId, id, paymentId, request);
  }

  @PostMapping("/payments/{paymentId}/refunds")
  public BillingAccountResponse refund(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID paymentId,
      @Valid @RequestBody RecordRefundRequest request) {
    return payments.refund(actor, organizationId, propertyId, id, paymentId, request);
  }

  @PostMapping("/allocations/{allocationId}/reverse")
  public BillingAccountResponse reverse(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID allocationId,
      @Valid @RequestBody BillingCommandRequest request) {
    return payments.reverse(actor, organizationId, propertyId, id, allocationId, request);
  }

  @PostMapping("/deposit/proposals/{settlementId}/cancel")
  public BillingAccountResponse cancelDeposit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID settlementId,
      @Valid @RequestBody BillingCommandRequest request) {
    return deposits.cancel(actor, organizationId, propertyId, id, settlementId, request);
  }
}
