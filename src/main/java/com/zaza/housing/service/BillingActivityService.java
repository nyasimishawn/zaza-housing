package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.BillingRepository;
import com.zaza.housing.util.AgreementDocuments;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class BillingActivityService {
  private final BillingRepository billing;
  private final AuditService audit;
  private final NotificationService notifications;
  private final ObjectMapper json;

  public BillingActivityService(
      BillingRepository billing,
      AuditService audit,
      NotificationService notifications,
      ObjectMapper json) {
    this.billing = billing;
    this.audit = audit;
    this.notifications = notifications;
    this.json = json;
  }

  private String fingerprint(Object request) {
    return AgreementDocuments.hash(
        json.writeValueAsString(canonical(json.convertValue(request, Object.class))));
  }

  private Object canonical(Object value) {
    if (value instanceof java.util.Map<?, ?> map) {
      var sorted = new java.util.TreeMap<String, Object>();
      map.forEach(
          (key, item) -> {
            if (!key.equals("password")) sorted.put(key.toString(), canonical(item));
          });
      return sorted;
    }
    if (value instanceof java.util.List<?> list) return list.stream().map(this::canonical).toList();
    return value;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean replay(
      AuthenticatedUser actor, UUID lease, UUID request, String action, Object body, long version) {
    var old = billing.operation(lease, request, actor.id(), action);
    if (old.isPresent()) {
      if (!old.get().equals(fingerprint(body)))
        throw BillingAccessService.conflict(
            "This request ID was already used for a different operation or payload.");
      return true;
    }
    if (billing.find(lease).orElseThrow().version() != version)
      throw BillingAccessService.conflict("Billing changed. Reload before continuing.");
    return false;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      AuthenticatedUser actor,
      UUID organization,
      RentalLease lease,
      UUID request,
      String action,
      Object body,
      UUID entity,
      String message,
      boolean initial) {
    billing.operation(lease.id(), request, actor.id(), action, fingerprint(body));
    if (!initial) billing.touch(lease.id());
    UUID event = billing.event(lease.id(), actor.id(), action, entity, message);
    audit.recordChange(actor.id(), organization, "BILLING_" + action, entity, null, message);
    notifications.billingChanged(
        actor.id(),
        lease.tenantId(),
        lease.applicationId(),
        lease.id(),
        lease.propertyId(),
        event,
        action,
        message,
        organization != null);
  }
}
