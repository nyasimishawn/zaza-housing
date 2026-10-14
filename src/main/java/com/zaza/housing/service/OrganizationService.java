package com.zaza.housing.service;

import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Member;
import com.zaza.housing.model.Organization;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.util.EmailNormalizer;
import com.zaza.housing.util.Pagination;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
    private final OrganizationRepository organizations;
    private final AuditService audit;

    public OrganizationService(OrganizationRepository organizations, AuditService audit) {
        this.organizations = organizations;
        this.audit = audit;
    }

    @Transactional
    public Organization create(AuthenticatedUser actor, String name, OrganizationType type) {
        Organization organization = organizations.create(actor.id(), name.trim(), type);
        audit.record(actor.id(), organization.id(), "ORGANIZATION_CREATED", organization.id());
        return organization;
    }

    public List<Organization> list(AuthenticatedUser actor, int limit, int offset) {
        Pagination.validate(limit, offset);
        return organizations.listForUser(actor.id(), limit, offset);
    }

    public Organization get(AuthenticatedUser actor, UUID id) {
        return organizations.findForUser(id, actor.id()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Organization not found."));
    }

    @Transactional
    public Organization rename(AuthenticatedUser actor, UUID id, String name) {
        organizations.lock(id);
        Organization previous = get(actor, id);
        requireManager(previous.role());
        organizations.rename(id, name.trim());
        audit.recordChange(actor.id(), id, "ORGANIZATION_RENAMED", id, previous.name(), name.trim());
        return get(actor, id);
    }

    public List<Member> members(AuthenticatedUser actor, UUID id, int limit, int offset) {
        Pagination.validate(limit, offset);
        requireManager(get(actor, id).role());
        return organizations.listMembers(id, limit, offset);
    }

    @Transactional
    public Member addMember(AuthenticatedUser actor, UUID id, String email, MembershipRole role) {
        organizations.lock(id);
        MembershipRole actorRole = get(actor, id).role();
        requireManager(actorRole);
        checkRoleChange(actorRole, null, role);
        UUID user = organizations.activeUserByEmail(EmailNormalizer.normalize(email)).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Registered account not found."));
        organizations.addMember(id, user, role);
        audit.recordChange(actor.id(), id, "MEMBER_ADDED", user, null, role.name());
        return organizations.member(id, user).orElseThrow();
    }

    @Transactional
    public Member changeRole(AuthenticatedUser actor, UUID id, UUID user, MembershipRole role) {
        organizations.lock(id);
        MembershipRole actorRole = get(actor, id).role();
        requireManager(actorRole);
        Member member = organizations.member(id, user).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found."));
        checkRoleChange(actorRole, member.role(), role);
        organizations.updateMember(id, user, role);
        audit.recordChange(actor.id(), id, "MEMBER_ROLE_CHANGED", user, member.role().name(), role.name());
        return organizations.member(id, user).orElseThrow();
    }

    @Transactional
    public void removeMember(AuthenticatedUser actor, UUID id, UUID user) {
        organizations.lock(id);
        MembershipRole actorRole = get(actor, id).role();
        requireManager(actorRole);
        Member member = organizations.member(id, user).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found."));
        checkRoleChange(actorRole, member.role(), MembershipRole.MEMBER);
        organizations.removeMember(id, user);
        audit.recordChange(actor.id(), id, "MEMBER_REMOVED", user, member.role().name(), null);
    }

    private static void requireManager(MembershipRole role) {
        if (role == MembershipRole.MEMBER) throw new ApiException(HttpStatus.FORBIDDEN, "Organization management permission is required.");
    }

    private static void checkRoleChange(MembershipRole actor, MembershipRole existing, MembershipRole requested) {
        if (existing == MembershipRole.OWNER || requested == MembershipRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Ownership cannot be changed through member management.");
        }
        if (actor == MembershipRole.ADMIN && (existing == MembershipRole.ADMIN || requested == MembershipRole.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the owner can manage organization administrators.");
        }
    }

}
