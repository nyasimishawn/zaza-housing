package com.zaza.housing.controller;
import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.PropertyDocumentService;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/documents")
public class PropertyDocumentController {
 private final PropertyDocumentService service;public PropertyDocumentController(PropertyDocumentService service){this.service=service;}
 @GetMapping public List<PropertyDocumentResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId,@RequestParam(defaultValue="20")int limit,@RequestParam(defaultValue="0")int offset){return service.list(actor,organizationId,propertyId,limit,offset);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public PropertyDocumentResponse attach(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId,@Valid @RequestBody PropertyDocumentRequest request){return service.attach(actor,organizationId,propertyId,request);}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void detach(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId,@PathVariable UUID id){service.detach(actor,organizationId,propertyId,id);}
}
