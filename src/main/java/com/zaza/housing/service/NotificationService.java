package com.zaza.housing.service;

import com.zaza.housing.dto.NotificationResponse;
import com.zaza.housing.dto.NotificationSummaryResponse;
import com.zaza.housing.enums.ApplicationEventType;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ServicePaymentAccount;
import com.zaza.housing.model.WorkRequest;
import com.zaza.housing.repository.NotificationRepository;
import com.zaza.housing.util.Pagination;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final NotificationRepository notifications;

  public NotificationService(NotificationRepository notifications) {
    this.notifications = notifications;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void applicationChanged(
      UUID applicant, UUID application, UUID event, ApplicationEventType type, String message) {
    notifications.append(applicant, application, event, type, message);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void leaseChanged(
      UUID actor,
      UUID tenant,
      UUID application,
      UUID lease,
      UUID property,
      UUID event,
      String type,
      String message,
      boolean notifyTenant) {
    if (notifyTenant) notifications.leaseTenant(tenant, application, lease, event, type, message);
    else notifications.leaseManagers(actor, application, lease, property, event, type, message);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void billingChanged(
      UUID actor,
      UUID tenant,
      UUID application,
      UUID lease,
      UUID property,
      UUID event,
      String type,
      String message,
      boolean notifyTenant) {
    if (notifyTenant) notifications.billingTenant(tenant, application, lease, event, type, message);
    else notifications.billingManagers(actor, application, lease, property, event, type, message);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void workChanged(
      UUID actor,
      WorkRequest work,
      UUID previousProvider,
      UUID event,
      String type,
      String message,
      WorkVisibility visibility) {
    if (visibility == WorkVisibility.SHARED) {
      notifications.workRecipient(
          work.tenantId(),
          actor,
          work.id(),
          event,
          type,
          message,
          "/api/v1/me/work-requests/" + work.id());
      if (work.providerId() != null)
        notifications.workProvider(work.providerId(), actor, work.id(), event, type, message);
      if (previousProvider != null && !previousProvider.equals(work.providerId()))
        notifications.workProvider(
            previousProvider, actor, work.id(), event, type, "The work assignment was ended.");
    }
    if (work.kind() == WorkKind.MAINTENANCE)
      notifications.workManagers(work.propertyId(), actor, work.id(), event, type, message);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void serviceFinanceChanged(
      UUID actor, ServicePaymentAccount account, UUID event, String type) {
    String message =
        "Service financial records were updated. Open the service statement for details.";
    if (account.payerUserId() != null)
      notifications.serviceFinance(
          account.payerUserId(), actor, account.id(), event, type, message);
    if (account.payerOrganizationId() != null)
      notifications.serviceFinanceOrganization(
          account.payerOrganizationId(), actor, account.id(), event, type, message);
    notifications.serviceFinanceOrganization(
        account.providerId(), actor, account.id(), event, type, message);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void conversationChanged(
      UUID actor, UUID conversation, UUID event, String type, List<UUID> recipients, String url) {
    for (UUID user : recipients)
      notifications.conversation(
          user,
          actor,
          conversation,
          event,
          type,
          url == null ? "/api/v1/conversations/" + conversation : url);
  }

  public List<NotificationResponse> list(
      AuthenticatedUser actor, boolean unreadOnly, int limit, int offset) {
    Pagination.validate(limit, offset);
    return notifications.list(actor.id(), unreadOnly, limit, offset);
  }

  public NotificationSummaryResponse summary(AuthenticatedUser actor) {
    return new NotificationSummaryResponse(notifications.unread(actor.id()));
  }

  public List<NotificationResponse> list(
      AuthenticatedUser actor,
      boolean unreadOnly,
      boolean archived,
      String category,
      int limit,
      int offset) {
    Pagination.validate(limit, offset);
    if (category != null)
      try {
        com.zaza.housing.enums.NotificationCategory.valueOf(category);
      } catch (IllegalArgumentException invalid) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid notification category.");
      }
    return notifications.list(actor.id(), unreadOnly, archived, category, limit, offset);
  }

  @Transactional
  public NotificationResponse markRead(AuthenticatedUser actor, UUID id) {
    return notifications
        .markRead(actor.id(), id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Notification not found."));
  }
}
