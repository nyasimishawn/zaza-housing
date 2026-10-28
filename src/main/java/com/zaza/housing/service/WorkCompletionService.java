package com.zaza.housing.service;

import com.zaza.housing.dto.CompleteWorkRequest;
import com.zaza.housing.dto.ConsentWorkRequest;
import com.zaza.housing.dto.CreateServiceReviewRequest;
import com.zaza.housing.dto.WorkCommandRequest;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkCompletionService {
  private final WorkCommandService commands;
  private final WorkRequestRepository work;
  private final OrganizationRepository memberships;
  private final Clock clock;

  public WorkCompletionService(
      WorkCommandService commands,
      WorkRequestRepository work,
      OrganizationRepository memberships,
      Clock clock) {
    this.commands = commands;
    this.work = work;
    this.memberships = memberships;
    this.clock = clock;
  }

  @Transactional
  public WorkView submit(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      CompleteWorkRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "COMPLETION_SUBMITTED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkVisitService.worker(a);
    WorkAccessService.state(a.work(), WorkStatus.IN_PROGRESS);
    work.completion(id, request.summary().trim(), WorkStatus.AWAITING_CONFIRMATION);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "COMPLETION_SUBMITTED",
        request,
        request.summary().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView confirm(AuthenticatedUser actor, UUID id, ConsentWorkRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.CUSTOMER,
            null,
            null,
            id,
            request.requestId(),
            "COMPLETION_CONFIRMED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.AWAITING_CONFIRMATION);
    if (a.work().kind() == WorkKind.MAINTENANCE) work.state(id, WorkStatus.AWAITING_APPROVAL);
    else work.completed(id, clock.instant());
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "COMPLETION_CONFIRMED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView approve(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, ConsentWorkRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.COORDINATOR,
            org,
            property,
            id,
            request.requestId(),
            "COMPLETION_APPROVED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    if(memberships.member(org,actor.id()).filter(member->member.role()!=com.zaza.housing.enums.MembershipRole.MEMBER).isEmpty())throw WorkAccessService.forbidden("An owner or agency administrator must approve the final maintenance completion.");
    WorkAccessService.state(a.work(), WorkStatus.AWAITING_APPROVAL);
    work.completed(id, clock.instant());
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "COMPLETION_APPROVED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView dispute(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      WorkCommandRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "DISPUTED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(
        a.work(),
        WorkStatus.SCHEDULED,
        WorkStatus.IN_PROGRESS,
        WorkStatus.AWAITING_CONFIRMATION,
        WorkStatus.AWAITING_APPROVAL,
        WorkStatus.COMPLETED);
    if (a.work().status() == WorkStatus.COMPLETED
        && (party != WorkParty.CUSTOMER
            || clock.instant().isAfter(a.work().completedAt().plus(Duration.ofDays(14)))))
      throw WorkAccessService.conflict(
          "Only the customer can reopen completed work, within 14 days.");
    work.dispute(a.work(), party, request.message().trim());
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "DISPUTED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView resolve(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      WorkCommandRequest request,
      boolean rework) {
    String action = rework ? "REWORK_REQUESTED" : "DISPUTE_WITHDRAWN";
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            action,
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.DISPUTED);
    if (rework) {
      if (a.work().kind() == WorkKind.MAINTENANCE)
        WorkAccessService.party(a, WorkParty.COORDINATOR);
      else WorkAccessService.party(a, WorkParty.CUSTOMER);
    } else if (work.disputeParty(a.work().disputeId()) != party)
      throw WorkAccessService.forbidden("Only the party that raised the dispute may withdraw it.");
    work.resolve(a.work(), actor.id(), rework ? "REWORK" : "WITHDRAWN", request.message().trim());
    return commands.finish(
        actor,
        a,
        request.requestId(),
        action,
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView review(AuthenticatedUser actor, UUID id, CreateServiceReviewRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.CUSTOMER,
            null,
            null,
            id,
            request.requestId(),
            "REVIEW_PUBLISHED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.COMPLETED);
    if (a.work().providerId() == null)
      throw WorkAccessService.conflict(
          "Internally handled maintenance has no marketplace provider to rate.");
    memberships.lock(a.work().providerId());
    if (memberships.member(a.work().providerId(), actor.id()).isPresent())
      throw WorkAccessService.forbidden(
          "Provider members cannot publish a review of their own organization.");
    work.review(a.work(), actor.id(), request);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "REVIEW_PUBLISHED",
        request,
        "The customer published a provider review.",
        WorkVisibility.SHARED);
  }
}
