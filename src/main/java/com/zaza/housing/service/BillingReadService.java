package com.zaza.housing.service;

import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingEntryResponse;
import com.zaza.housing.dto.DepositSettlementResponse;
import com.zaza.housing.dto.InvoiceResponse;
import com.zaza.housing.dto.PaymentAllocationResponse;
import com.zaza.housing.dto.PaymentClaimResponse;
import com.zaza.housing.dto.PaymentReceiptResponse;
import com.zaza.housing.dto.PaymentResponse;
import com.zaza.housing.dto.RefundResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.BillingRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BillingReadService {
  private final BillingAccessService access;
  private final BillingRepository billing;
  private final Clock clock;

  public BillingReadService(BillingAccessService access, BillingRepository billing, Clock clock) {
    this.access = access;
    this.billing = billing;
    this.clock = clock;
  }

  private void authorize(AuthenticatedUser actor, UUID organization, UUID property, UUID id) {
    access.lease(actor, organization, property, id, false);
    access.account(id);
  }

  public BillingAccountResponse account(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id) {
    authorize(actor, organization, property, id);
    return billing.summary(id, LocalDate.now(clock));
  }

  public List<InvoiceResponse> invoices(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.invoices(id, LocalDate.now(clock), limit, offset);
  }

  public List<PaymentResponse> payments(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.payments(id, limit, offset);
  }

  public List<PaymentClaimResponse> claims(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.claims(id, limit, offset);
  }

  public List<PaymentAllocationResponse> allocations(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.allocations(id, limit, offset);
  }

  public List<RefundResponse> refunds(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.refunds(id, limit, offset);
  }

  public List<DepositSettlementResponse> settlements(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.settlements(id, limit, offset);
  }

  public List<BillingEntryResponse> history(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, int limit, int offset) {
    authorize(actor, organization, property, id);
    Pagination.validate(limit, offset);
    return billing.history(id, limit, offset);
  }

  public PaymentReceiptResponse receipt(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, UUID payment) {
    authorize(actor, organization, property, id);
    var record =
        billing
            .payment(id, payment)
            .orElseThrow(() -> BillingAccessService.missing("Payment receipt not found."));
    return new PaymentReceiptResponse(
        payment,
        id,
        access.account(id).currency(),
        "MANUALLY_VERIFIED_EXTERNAL_PAYMENT",
        record,
        billing.paymentAllocations(id, payment),
        billing.paymentRefunds(id, payment));
  }

  public String receiptText(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, UUID payment) {
    var receipt = receipt(actor, organization, property, id, payment);
    var r = receipt.payment();
    return "ZAZA PAYMENT RECEIPT\nReceipt: "
        + r.id()
        + "\nLease: "
        + id
        + "\nAmount received: "
        + receipt.currency()
        + " "
        + r.amount().toPlainString()
        + "\nChannel: "
        + r.channel()
        + "\nExternal reference: "
        + r.reference()
        + "\nReceived on: "
        + r.receivedOn()
        + "\nVerified by account: "
        + r.verifiedBy()
        + "\nRecorded at: "
        + r.createdAt()
        + "\nStatus: "
        + (r.voided() ? "VOID" : "MANUALLY VERIFIED")
        + "\n"
        + "Verification is an owner administrator's confirmation of an external payment. This"
        + " receipt does not initiate a transfer.\n";
  }
}
