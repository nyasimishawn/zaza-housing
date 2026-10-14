package com.zaza.housing.property;

import com.zaza.housing.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.zaza.housing.property.PropertyRequests.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties")
public class PropertyController {
    private final PropertyService properties;

    public PropertyController(PropertyService properties) { this.properties = properties; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Property create(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @Valid @RequestBody PropertyDetails request) {
        return properties.create(actor, organizationId, request);
    }

    @GetMapping
    public List<Property> list(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @RequestParam(required = false) PropertyStatus status, @RequestParam(required = false) PropertyType type,
            @RequestParam(required = false) String city, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return properties.list(actor, organizationId, status, type, city, limit, offset);
    }

    @GetMapping("/{id}")
    public Property get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID id) {
        return properties.get(actor, organizationId, id);
    }

    @PutMapping("/{id}")
    public Property update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID id, @Valid @RequestBody UpdateProperty request) {
        return properties.update(actor, organizationId, id, request);
    }

    @PatchMapping("/{id}/status")
    public Property changeStatus(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID id, @Valid @RequestBody ChangePropertyStatus request) {
        return properties.changeStatus(actor, organizationId, id, request);
    }

    @PatchMapping("/{id}/management")
    public Property changeManagement(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID id, @Valid @RequestBody ChangeManagement request) {
        return properties.changeManagement(actor, organizationId, id, request);
    }

    @PostMapping("/{propertyId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyUnit createUnit(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @Valid @RequestBody UnitDetails request) {
        return properties.createUnit(actor, organizationId, propertyId, request);
    }

    @GetMapping("/{propertyId}/units")
    public List<PropertyUnit> units(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @RequestParam(required = false) UnitAvailability availability,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return properties.units(actor, organizationId, propertyId, availability, includeArchived, limit, offset);
    }

    @GetMapping("/{propertyId}/units/{id}")
    public PropertyUnit unit(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id) {
        return properties.unit(actor, organizationId, propertyId, id);
    }

    @PutMapping("/{propertyId}/units/{id}")
    public PropertyUnit updateUnit(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody UpdateUnit request) {
        return properties.updateUnit(actor, organizationId, propertyId, id, request);
    }

    @PatchMapping("/{propertyId}/units/{id}/status")
    public PropertyUnit changeUnitStatus(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId,
            @PathVariable UUID propertyId, @PathVariable UUID id, @Valid @RequestBody ChangeUnitStatus request) {
        return properties.changeUnitStatus(actor, organizationId, propertyId, id, request);
    }
}
