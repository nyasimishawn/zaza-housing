package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.*;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamInvitationService {
  private final TeamInvitationRepository invitations;
  private final OrganizationRepository organizations;
  private final NotificationWorkspaceRepository notifications;
  private final SecurityCryptography crypto;
  private final AuditService audit;
  private final Clock clock;

  public TeamInvitationService(
      TeamInvitationRepository invitations,
      OrganizationRepository organizations,
      NotificationWorkspaceRepository notifications,
      SecurityCryptography crypto,
      AuditService audit,
      Clock clock) {
    this.invitations = invitations;
    this.organizations = organizations;
    this.notifications = notifications;
    this.crypto = crypto;
    this.audit = audit;
    this.clock = clock;
  }

  private ApiException error(HttpStatus status, String message) {
    return new ApiException(status, message);
  }

  private MembershipRole manager(AuthenticatedUser actor, UUID org, String role) {
    var membership =
        organizations
            .findForUser(org, actor.id())
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Business not found."));
    if (membership.role() == MembershipRole.MEMBER
        || ("ADMIN".equals(role) && membership.role() != MembershipRole.OWNER))
      throw error(
          HttpStatus.FORBIDDEN,
          "Current owner permission is required to manage administrator invitations.");
    return membership.role();
  }

  private TeamInvitation get(UUID id, boolean lock) {
    return invitations
        .find(id, lock)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Invitation not found."));
  }

  private TeamInvitationResponse response(TeamInvitation i) {
    return TeamInvitationResponse.of(i, clock.instant());
  }

  public List<TeamInvitationResponse> list(
      AuthenticatedUser actor, UUID org, String status, int limit, int offset) {
    Pagination.validate(limit, offset);
    if (org != null) manager(actor, org, null);
    else if (!invitations.verified(actor.id()))
      throw error(HttpStatus.FORBIDDEN, "Verify your email before viewing invitations.");
    if (status != null
        && !Set.of("PENDING", "ACCEPTED", "DECLINED", "CANCELLED", "EXPIRED").contains(status))
      throw error(HttpStatus.BAD_REQUEST, "Invalid invitation status.");
    return invitations.list(org, actor.email(), status, limit, offset).stream()
        .map(this::response)
        .toList();
  }

  public List<TeamInvitationEventResponse> history(
      AuthenticatedUser actor, UUID org, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    var i = get(id, false);
    if (org != null) {
      if (!org.equals(i.organizationId()))
        throw error(HttpStatus.NOT_FOUND, "Invitation not found.");
      manager(actor, org, null);
    } else recipient(actor, i);
    return invitations.history(id, limit, offset);
  }

  public TeamInvitationResponse detail(AuthenticatedUser actor, UUID org, UUID id) {
    var i = get(id, false);
    if (org == null) recipient(actor, i);
    else {
      if (!org.equals(i.organizationId()))
        throw error(HttpStatus.NOT_FOUND, "Invitation not found.");
      manager(actor, org, null);
    }
    return response(i);
  }

  @Transactional
  public TeamInvitationResponse create(
      AuthenticatedUser actor, UUID org, CreateTeamInvitationRequest r) {
    organizations.lock(org);
    manager(actor, org, r.role().name());
    if (r.role() == MembershipRole.OWNER)
      throw error(HttpStatus.FORBIDDEN, "Ownership cannot be invited.");
    if (!invitations.verified(actor.id()))
      throw error(
          HttpStatus.FORBIDDEN, "Verify your email in Account security before inviting a team.");
    String email = EmailNormalizer.normalize(r.email());
    Instant now = clock.instant();
    var existing = invitations.request(org, r.requestId());
    if (existing.isPresent()) {
      var i = existing.get();
      if (!i.email().equals(email) || !i.role().equals(r.role().name()))
        throw error(
            HttpStatus.CONFLICT, "This request was already used for a different invitation.");
      return response(i);
    }
    invitations.expire(org, email, now);
    if (invitations.pending(org, email))
      throw error(HttpStatus.CONFLICT, "A pending invitation already exists. Resend or cancel it.");
    var candidate = organizations.activeUserByEmail(email);
    if (candidate.isPresent() && organizations.member(org, candidate.get()).isPresent())
      throw error(HttpStatus.CONFLICT, "This account is already a team member.");
    if (invitations.recent(org, now.minusSeconds(86400)) >= 50)
      throw error(
          HttpStatus.TOO_MANY_REQUESTS, "This business has reached its daily invitation limit.");
    UUID id = UUID.randomUUID();
    String token = token();
    invitations.create(
        id,
        org,
        email,
        r.role().name(),
        actor.id(),
        r.requestId(),
        TokenUtils.hashToken(token),
        now,
        now.plusSeconds(604800));
    var i = get(id, false);
    queue(i, token);
    event(actor, i, "CREATED");
    candidate.ifPresent(
        user ->
            notifications.event(
                user,
                "INVITATION",
                id,
                "TEAM_INVITATION_RECEIVED",
                "You have a business team invitation. Review the business and access before"
                    + " accepting.",
                "invitation:" + id + ":created",
                now));
    return response(get(id, false));
  }

  private String token() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void queue(TeamInvitation i, String token) {
    UUID id = UUID.randomUUID();
    String body =
        "Zaza team invitation\n\nBusiness: "
            + i.organizationName()
            + "\nRole: "
            + i.role()
            + "\n\n"
            + "Sign in or create an account using this email address, verify it in Account"
            + " security, then open Team invitations. You can also paste this invitation code"
            + " there:\n\n"
            + token
            + "\n\nExpires at "
            + i.expiresAt()
            + ". No business access is granted until you accept. Ignore or decline invitations you"
            + " do not expect.";
    invitations.queue(
        id,
        i.id(),
        crypto.encrypt("DELIVERY|" + id, body.getBytes(StandardCharsets.UTF_8)),
        clock.instant(),
        i.expiresAt(),
        i.sendCount());
  }

  private void event(AuthenticatedUser actor, TeamInvitation i, String action) {
    invitations.event(i.id(), actor.id(), action, clock.instant());
    audit.record(actor.id(), i.organizationId(), "TEAM_INVITATION_" + action, i.id());
  }

  private void pending(TeamInvitation i, long version) {
    if (i.version() != version)
      throw error(HttpStatus.CONFLICT, "Invitation changed. Reload before continuing.");
    if (!i.status().equals("PENDING") || !i.expiresAt().isAfter(clock.instant()))
      throw error(HttpStatus.CONFLICT, "This invitation is no longer pending.");
  }

  @Transactional
  public TeamInvitationResponse manage(
      AuthenticatedUser actor, UUID org, UUID id, long version, String action) {
    organizations.lock(org);
    var i = get(id, true);
    if (!org.equals(i.organizationId())) throw error(HttpStatus.NOT_FOUND, "Invitation not found.");
    manager(actor, org, i.role());
    pending(i, version);
    if (action.equals("resend")) {
      if (i.sendCount() >= 5 || i.lastSentAt().plusSeconds(60).isAfter(clock.instant()))
        throw error(
            HttpStatus.TOO_MANY_REQUESTS,
            "Wait at least a minute between resends. An invitation can be sent five times.");
      var inviter = organizations.findForUser(org, i.invitedBy());
      if (!invitations.verified(i.invitedBy())
          || inviter.isEmpty()
          || inviter.get().role() == MembershipRole.MEMBER
          || ("ADMIN".equals(i.role()) && inviter.get().role() != MembershipRole.OWNER))
        throw error(
            HttpStatus.CONFLICT,
            "The original inviter no longer has permission. Cancel this invitation and create a new"
                + " one.");
      String token = token();
      invitations.rotate(
          id, TokenUtils.hashToken(token), clock.instant(), clock.instant().plusSeconds(604800));
      queue(get(id, false), token);
      event(actor, i, "RESENT");
    } else {
      invitations.state(id, "CANCELLED", null, clock.instant());
      invitations.cancelDelivery(id);
      event(actor, i, "CANCELLED");
    }
    return response(get(id, false));
  }

  private void recipient(AuthenticatedUser actor, TeamInvitation i) {
    if (!EmailNormalizer.normalize(actor.email()).equals(i.email()))
      throw error(HttpStatus.NOT_FOUND, "Invitation not found.");
    if (!invitations.verified(actor.id()))
      throw error(HttpStatus.FORBIDDEN, "Verify your email before viewing invitations.");
  }

  @Transactional
  public TeamInvitationResponse respond(
      AuthenticatedUser actor, UUID id, long version, boolean accept) {
    var initial = get(id, false);
    recipient(actor, initial);
    organizations.lock(initial.organizationId());
    var i = get(id, true);
    recipient(actor, i);
    pending(i, version);
    if (accept) {
      if (!invitations.verified(actor.id()))
        throw error(
            HttpStatus.FORBIDDEN,
            "Verify the invited email address in Account security before accepting.");
      var inviter = organizations.findForUser(i.organizationId(), i.invitedBy());
      if (!invitations.verified(i.invitedBy())
          || inviter.isEmpty()
          || inviter.get().role() == MembershipRole.MEMBER
          || ("ADMIN".equals(i.role()) && inviter.get().role() != MembershipRole.OWNER))
        throw error(
            HttpStatus.CONFLICT,
            "The inviter no longer has permission. Ask the business owner for a new invitation.");
      if (organizations.member(i.organizationId(), actor.id()).isPresent())
        throw error(
            HttpStatus.CONFLICT,
            "You are already a member. Ask the owner to review your current role.");
      organizations.addMember(i.organizationId(), actor.id(), MembershipRole.valueOf(i.role()));
    }
    invitations.state(
        id, accept ? "ACCEPTED" : "DECLINED", accept ? actor.id() : null, clock.instant());
    invitations.cancelDelivery(id);
    event(actor, i, accept ? "ACCEPTED" : "DECLINED");
    notifications.event(
        i.invitedBy(),
        "INVITATION",
        id,
        accept ? "TEAM_INVITATION_ACCEPTED" : "TEAM_INVITATION_DECLINED",
        "A team invitation was " + (accept ? "accepted." : "declined."),
        "invitation:" + id + ":response",
        clock.instant());
    return response(get(id, false));
  }

  @Transactional
  public TeamInvitationResponse redeem(AuthenticatedUser actor, String token) {
    var i =
        invitations
            .token(TokenUtils.hashToken(token))
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Invitation not found."));
    return respond(actor, i.id(), i.version(), true);
  }

  public TeamInvitationResponse preview(AuthenticatedUser actor, String token) {
    var i =
        invitations
            .token(TokenUtils.hashToken(token))
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Invitation not found."));
    recipient(actor, i);
    return response(i);
  }
}
