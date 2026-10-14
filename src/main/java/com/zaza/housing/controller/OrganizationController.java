package com.zaza.housing.controller;

import com.zaza.housing.dto.AddMemberRequest;
import com.zaza.housing.dto.ChangeMemberRoleRequest;
import com.zaza.housing.dto.CreateOrganizationRequest;
import com.zaza.housing.dto.RenameOrganizationRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Member;
import com.zaza.housing.model.Organization;
import com.zaza.housing.service.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) { this.organizations = organizations; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Organization create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateOrganizationRequest request) {
        return organizations.create(user, request.name(), request.type());
    }

    @GetMapping
    public List<Organization> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return organizations.list(user, limit, offset);
    }

    @GetMapping("/{id}")
    public Organization get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return organizations.get(user, id);
    }

    @PatchMapping("/{id}")
    public Organization rename(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @Valid @RequestBody RenameOrganizationRequest request) {
        return organizations.rename(user, id, request.name());
    }

    @GetMapping("/{id}/members")
    public List<Member> members(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return organizations.members(user, id, limit, offset);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public Member addMember(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @Valid @RequestBody AddMemberRequest request) {
        return organizations.addMember(user, id, request.email(), request.role());
    }

    @PatchMapping("/{id}/members/{userId}")
    public Member changeRole(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @PathVariable UUID userId, @Valid @RequestBody ChangeMemberRoleRequest request) {
        return organizations.changeRole(user, id, userId, request.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @PathVariable UUID userId) {
        organizations.removeMember(user, id, userId);
    }

}
