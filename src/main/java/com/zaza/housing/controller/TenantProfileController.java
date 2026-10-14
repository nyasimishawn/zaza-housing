package com.zaza.housing.controller;

import com.zaza.housing.dto.TenantProfileDetails;
import com.zaza.housing.dto.TenantProfileResponse;
import com.zaza.housing.dto.UpdateTenantProfileRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.TenantProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/tenant-profile")
public class TenantProfileController {
    private final TenantProfileService profiles;
    public TenantProfileController(TenantProfileService profiles) { this.profiles = profiles; }
    @GetMapping
    public TenantProfileResponse get(@AuthenticationPrincipal AuthenticatedUser actor) { return profiles.get(actor); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public TenantProfileResponse create(@AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody TenantProfileDetails request) { return profiles.create(actor, request); }
    @PutMapping
    public TenantProfileResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody UpdateTenantProfileRequest request) { return profiles.update(actor, request); }
}
