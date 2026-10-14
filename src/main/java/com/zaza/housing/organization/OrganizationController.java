package com.zaza.housing.organization;

import com.zaza.housing.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) { this.organizations = organizations; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Organization create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateRequest request) {
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
    public Organization rename(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
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
            @PathVariable UUID userId, @Valid @RequestBody RoleRequest request) {
        return organizations.changeRole(user, id, userId, request.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @PathVariable UUID userId) {
        organizations.removeMember(user, id, userId);
    }

    public record CreateRequest(@NotBlank @Size(max = 150) String name, @NotNull OrganizationType type) { }
    public record RenameRequest(@NotBlank @Size(max = 150) String name) { }
    public record AddMemberRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull MembershipRole role) { }
    public record RoleRequest(@NotNull MembershipRole role) { }
}
