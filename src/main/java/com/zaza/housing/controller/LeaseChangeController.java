package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.LeaseChangeService;
import com.zaza.housing.util.AgreementHttpResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/me/leases/{id}/changes","/api/v1/organizations/{organizationId}/properties/{propertyId}/leases/{id}/changes"})
public class LeaseChangeController {
    private final LeaseChangeService changes;
    public LeaseChangeController(LeaseChangeService changes) { this.changes=changes; }
    @GetMapping
    public List<LeaseChangeResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id) {
        return changes.list(actor,organizationId,propertyId,id);
    }
    @PostMapping("/preview")
    public LeaseTerminationPreviewResponse preview(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id,@Valid @RequestBody LeaseChangePreviewRequest request) {
        return changes.preview(actor,organizationId,propertyId,id,request);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public LeaseChangeResponse create(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id,@Valid @RequestBody CreateLeaseChangeRequest request) {
        return changes.create(actor,organizationId,propertyId,id,request);
    }
    @GetMapping("/{changeId}/document")
    public ResponseEntity<String> document(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id,@PathVariable UUID changeId) {
        return AgreementHttpResponse.document(changes.document(actor,organizationId,propertyId,id,changeId).document(),"lease-change-"+changeId+".txt");
    }
    @PostMapping("/{changeId}/accept")
    public LeaseChangeResponse accept(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id,@PathVariable UUID changeId,@Valid @RequestBody ConfirmLeaseTerminationRequest request) {
        return changes.accept(actor,organizationId,propertyId,id,changeId,request);
    }
    @PostMapping("/{changeId}/reject")
    public LeaseChangeResponse reject(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id,@PathVariable UUID changeId,@Valid @RequestBody LeaseReasonRequest request) {
        return changes.resolve(actor,organizationId,propertyId,id,changeId,request,false);
    }
    @PostMapping("/{changeId}/cancel")
    public LeaseChangeResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable(required=false) UUID organizationId,@PathVariable(required=false) UUID propertyId,@PathVariable UUID id,@PathVariable UUID changeId,@Valid @RequestBody LeaseReasonRequest request) {
        return changes.resolve(actor,organizationId,propertyId,id,changeId,request,true);
    }
}
