package com.zaza.housing.service;

import com.zaza.housing.dto.WorkDecisionResponse;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.model.WorkMutation;
import com.zaza.housing.repository.ServicePaymentRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkCommandService {
  private final ServicePaymentRepository servicePayments;
  private final WorkAccessService access;
  private final WorkActivityService activity;
  private final WorkReadService reads;

  public WorkCommandService(
      WorkAccessService access,
      WorkActivityService activity,
      WorkReadService reads,
      ServicePaymentRepository servicePayments) {
    this.servicePayments = servicePayments;
    this.access = access;
    this.activity = activity;
    this.reads = reads;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public WorkMutation begin(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      UUID request,
      String action,
      Object body,
      long version) {
    var a = access.get(actor, party, org, property, id, true);

    boolean replay = activity.replay(actor, a, request, action, body, version);
    if (!replay
        && Set.of(
                "QUOTE_PROPOSED",
                "CANCELLED",
                "REJECTED",
                "PROVIDER_DECLINED",
                "PROVIDER_RELEASED",
                "ASSIGNED",
                "INTERNAL_ASSIGNED")
            .contains(action)
        && servicePayments.blocksWorkChange(id))
      throw WorkAccessService.conflict(
          "Refund and close the open service financial account before changing quotes or ending the"
              + " assignment.");
    return new WorkMutation(a, replay);
  }

  public WorkView response(WorkAccess a) {
    var current = access.find(a.work().id());
    if (a.party() == WorkParty.PROVIDER && !a.organizationId().equals(current.providerId()))
      return new WorkDecisionResponse(current.id(), current.version(), current.status());
    return reads.response(new WorkAccess(current, a.party(), a.organizationId()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public WorkView finish(
      AuthenticatedUser actor,
      WorkAccess a,
      UUID request,
      String action,
      Object body,
      String message,
      WorkVisibility visibility) {
    activity.record(actor, a, a.work().id(), request, action, body, message, visibility, false);
    return response(a);
  }
}
