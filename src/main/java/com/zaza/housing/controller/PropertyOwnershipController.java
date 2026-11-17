package com.zaza.housing.controller;
import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.PropertyOwnershipService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public class PropertyOwnershipController {
    private final PropertyOwnershipService ownership;
    public PropertyOwnershipController(PropertyOwnershipService ownership) { this.ownership=ownership; }
    @GetMapping("/properties/{propertyId}/ownership")
    public List<PropertyInterestResponse> interests(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId) { return ownership.interests(actor,organizationId,propertyId); }
    @GetMapping("/ownership-proposals")
    public List<OwnershipProposalResponse> inbox(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) { return ownership.inbox(actor,organizationId,limit,offset); }
    @PostMapping("/properties/{propertyId}/ownership/preview")
    public LeaseTerminationPreviewResponse preview(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId,@Valid @RequestBody OwnershipPreviewRequest request) { return ownership.preview(actor,organizationId,propertyId,request); }
    @PostMapping("/properties/{propertyId}/ownership") @ResponseStatus(HttpStatus.CREATED)
    public OwnershipProposalResponse create(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId,@Valid @RequestBody CreateOwnershipProposalRequest request) { return ownership.create(actor,organizationId,propertyId,request); }
    @PostMapping("/ownership-proposals/{id}/accept")
    public OwnershipProposalResponse accept(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID id,@Valid @RequestBody ConfirmLeaseTerminationRequest request) { return ownership.decide(actor,organizationId,id,request); }
    @PostMapping("/ownership-proposals/{id}/reject")
    public OwnershipProposalResponse reject(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID id,@Valid @RequestBody LeaseReasonRequest request) { return ownership.resolve(actor,organizationId,id,request,false); }
    @PostMapping("/ownership-proposals/{id}/cancel")
    public OwnershipProposalResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID id,@Valid @RequestBody LeaseReasonRequest request) { return ownership.resolve(actor,organizationId,id,request,true); }
}
