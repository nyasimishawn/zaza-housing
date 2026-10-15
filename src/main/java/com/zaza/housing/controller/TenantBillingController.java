package com.zaza.housing.controller;

import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingEntryResponse;
import com.zaza.housing.dto.BillingPreviewResponse;
import com.zaza.housing.dto.DecideDepositRequest;
import com.zaza.housing.dto.DepositSettlementResponse;
import com.zaza.housing.dto.InvoiceResponse;
import com.zaza.housing.dto.PaymentAllocationResponse;
import com.zaza.housing.dto.PaymentClaimResponse;
import com.zaza.housing.dto.PaymentReceiptResponse;
import com.zaza.housing.dto.PaymentResponse;
import com.zaza.housing.dto.RefundResponse;
import com.zaza.housing.dto.ReportPaymentRequest;
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
@RequestMapping("/api/v1/me/leases/{id}/billing")
public class TenantBillingController {
  private final BillingReadService reads;
  private final BillingService billing;
  private final PaymentService payments;
  private final DepositService deposits;

  public TenantBillingController(
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
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reads.account(actor, null, null, id);
  }

  @GetMapping("/preview")
  public BillingPreviewResponse preview(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return billing.preview(actor, null, null, id);
  }

  @GetMapping("/invoices")
  public List<InvoiceResponse> invoices(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.invoices(actor, null, null, id, limit, offset);
  }

  @GetMapping("/payments")
  public List<PaymentResponse> payments(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.payments(actor, null, null, id, limit, offset);
  }

  @GetMapping("/claims")
  public List<PaymentClaimResponse> claims(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.claims(actor, null, null, id, limit, offset);
  }

  @GetMapping("/allocations")
  public List<PaymentAllocationResponse> allocations(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.allocations(actor, null, null, id, limit, offset);
  }

  @GetMapping("/refunds")
  public List<RefundResponse> refunds(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.refunds(actor, null, null, id, limit, offset);
  }

  @GetMapping("/settlements")
  public List<DepositSettlementResponse> settlements(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.settlements(actor, null, null, id, limit, offset);
  }

  @GetMapping("/history")
  public List<BillingEntryResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, null, null, id, limit, offset);
  }

  @GetMapping("/payments/{paymentId}/receipt")
  public PaymentReceiptResponse receipt(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID paymentId) {
    return reads.receipt(actor, null, null, id, paymentId);
  }

  @GetMapping("/payments/{paymentId}/receipt/document")
  public ResponseEntity<String> receiptDocument(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID paymentId) {
    return AgreementHttpResponse.document(
        reads.receiptText(actor, null, null, id, paymentId), "receipt-" + paymentId + ".txt");
  }

  @PostMapping("/claims")
  public BillingAccountResponse report(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ReportPaymentRequest request) {
    return payments.report(actor, id, request);
  }

  @PostMapping("/deposit/proposals/{settlementId}/decision")
  public BillingAccountResponse decideDeposit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID settlementId,
      @Valid @RequestBody DecideDepositRequest request) {
    return deposits.decide(actor, id, settlementId, request);
  }
}
