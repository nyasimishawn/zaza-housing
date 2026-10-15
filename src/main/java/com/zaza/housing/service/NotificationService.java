package com.zaza.housing.service;

import com.zaza.housing.dto.NotificationResponse;
import com.zaza.housing.dto.NotificationSummaryResponse;
import com.zaza.housing.enums.ApplicationEventType;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
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
    public NotificationService(NotificationRepository notifications) { this.notifications = notifications; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void applicationChanged(UUID applicant, UUID application, UUID event, ApplicationEventType type, String message) {
        notifications.append(applicant, application, event, type, message);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaseChanged(UUID actor,UUID tenant,UUID application,UUID lease,UUID property,UUID event,String type,String message,boolean notifyTenant) {
        if(notifyTenant) notifications.leaseTenant(tenant,application,lease,event,type,message);
        else notifications.leaseManagers(actor,application,lease,property,event,type,message);
    }
    public List<NotificationResponse> list(AuthenticatedUser actor, boolean unreadOnly, int limit, int offset) {
        Pagination.validate(limit, offset);
        return notifications.list(actor.id(), unreadOnly, limit, offset);
    }
    public NotificationSummaryResponse summary(AuthenticatedUser actor) { return new NotificationSummaryResponse(notifications.unread(actor.id())); }
    @Transactional
    public NotificationResponse markRead(AuthenticatedUser actor, UUID id) {
        return notifications.markRead(actor.id(), id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Notification not found."));
    }
}
