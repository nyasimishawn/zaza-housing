package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ServiceFinanceMutation;
import com.zaza.housing.model.ServicePaymentAccount;
import com.zaza.housing.repository.ServicePaymentRepository;
import com.zaza.housing.util.AgreementDocuments;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ServiceFinanceActivityService {
  private final ServicePaymentRepository payments;
  private final ServiceFinanceAccessService access;
  private final AuditService audit;
  private final NotificationService notifications;
  private final ObjectMapper json;

  public ServiceFinanceActivityService(
      ServicePaymentRepository payments,
      ServiceFinanceAccessService access,
      AuditService audit,
      NotificationService notifications,
      ObjectMapper json) {
    this.payments = payments;
    this.access = access;
    this.audit = audit;
    this.notifications = notifications;
    this.json = json;
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach(
          (k, v) -> {
            if (!k.toString().equalsIgnoreCase("password")) sorted.put(k.toString(), canonical(v));
          });
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
    return value;
  }

  private String hash(Object body) {
    return AgreementDocuments.hash(
        json.writeValueAsString(canonical(json.convertValue(body, Object.class))));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> replay(
      AuthenticatedUser actor, UUID scope, UUID request, String action, Object body) {
    var op = payments.operation(scope, request);
    if (op.isEmpty()) return Optional.empty();
    var old = op.get();
    if (!actor.id().equals(old.get("actor_id"))
        || !action.equals(old.get("action"))
        || !hash(body).equals(old.get("fingerprint")))
      throw WorkAccessService.conflict(
          "Request ID was used for a different actor, action or payload.");
    return Optional.of((UUID) old.get("entity_id"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public ServiceFinanceMutation begin(
      AuthenticatedUser actor, UUID id, UUID request, String action, Object body, long version) {
    var a = access.get(actor, id, true);
    if (replay(actor, id, request, action, body).isPresent())
      return new ServiceFinanceMutation(a, true);
    if (a.account().version() != version)
      throw WorkAccessService.conflict("Service finances changed. Reload before continuing.");
    if (!a.account().status().equals("OPEN"))
      throw WorkAccessService.conflict("The service account is closed.");
    return new ServiceFinanceMutation(a, false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void operation(
      AuthenticatedUser actor, UUID scope, UUID request, String action, Object body, UUID entity) {
    payments.operation(scope, request, actor.id(), action, hash(body), entity);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void finish(
      AuthenticatedUser actor,
      ServicePaymentAccount a,
      UUID request,
      String action,
      Object body,
      UUID entity,
      BigDecimal amount) {
    operation(actor, a.id(), request, action, body, entity);
    payments.touch(a.id());
    UUID event =
        payments.event(
            a.id(),
            actor.id(),
            action,
            entity,
            amount,
            "Service finance action: " + action.toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
    // Audit and notifications contain identifiers and safe action text, never passwords, routing
    // tokens or transfer evidence.
    audit.record(actor.id(), a.payerOrganizationId(), "SERVICE_FINANCE_" + action, entity);
    notifications.serviceFinanceChanged(actor.id(), a, event, action);
  }
}
