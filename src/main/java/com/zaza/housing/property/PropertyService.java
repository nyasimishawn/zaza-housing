package com.zaza.housing.property;

import com.zaza.housing.audit.AuditService;
import com.zaza.housing.auth.AuthenticatedUser;
import com.zaza.housing.common.ApiException;
import com.zaza.housing.organization.*;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static com.zaza.housing.property.PropertyRequests.*;

@Service
public class PropertyService {
    private static final Set<String> COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));
    private final PropertyRepository properties;
    private final OrganizationRepository memberships;
    private final OrganizationService organizations;
    private final AuditService audit;

    public PropertyService(PropertyRepository properties, OrganizationRepository memberships,
            OrganizationService organizations, AuditService audit) {
        this.properties = properties;
        this.memberships = memberships;
        this.organizations = organizations;
        this.audit = audit;
    }

    @Transactional
    public Property create(AuthenticatedUser actor, UUID organizationId, PropertyDetails details) {
        memberships.lock(organizationId);
        Organization organization = organizations.get(actor, organizationId);
        requireManager(organization);
        if (organization.type() == OrganizationType.SERVICE_PROVIDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only landlord and agency organizations can register properties.");
        }
        validate(details);
        UUID id = properties.create(organizationId, actor.id(), details);
        Property result = get(actor, organizationId, id);
        audit.recordSnapshot(actor.id(), organizationId, "PROPERTY_CREATED", id, null, result);
        return result;
    }

    public List<Property> list(AuthenticatedUser actor, UUID organizationId, PropertyStatus status,
            PropertyType type, String city, int limit, int offset) {
        organizations.get(actor, organizationId);
        OrganizationService.validatePage(limit, offset);
        if (city != null && (city.isBlank() || city.length() > 100)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "City must contain 1-100 characters.");
        }
        return properties.list(organizationId, status, type, city, limit, offset);
    }

    public Property get(AuthenticatedUser actor, UUID organizationId, UUID id) {
        return access(actor, organizationId, id, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Property lockForManagement(AuthenticatedUser actor, UUID organizationId, UUID id) {
        return access(actor, organizationId, id, true);
    }

    @Transactional
    public Property update(AuthenticatedUser actor, UUID organizationId, UUID id, UpdateProperty request) {
        Property previous = access(actor, organizationId, id, true);
        requireOpen(previous);
        checkVersion(previous.version(), request.version());
        validate(request.details());
        properties.update(id, request.version(), request.details());
        return recordProperty(actor, organizationId, previous, "PROPERTY_UPDATED");
    }

    @Transactional
    public Property changeStatus(AuthenticatedUser actor, UUID organizationId, UUID id, ChangePropertyStatus request) {
        Property previous = access(actor, organizationId, id, true);
        requireOwner(previous, organizationId);
        checkVersion(previous.version(), request.version());
        properties.changeStatus(id, request.version(), request.status());
        return recordProperty(actor, organizationId, previous, "PROPERTY_STATUS_CHANGED");
    }

    @Transactional
    public Property changeManagement(AuthenticatedUser actor, UUID organizationId, UUID id, ChangeManagement request) {
        Property previous = access(actor, organizationId, id, true);
        requireOwner(previous, organizationId);
        checkVersion(previous.version(), request.version());
        UUID agency = request.managerOrganizationId();
        if (agency != null) requireOpen(previous);
        if (agency != null && (agency.equals(organizationId) || !properties.isAgency(agency))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a separate agency organization for management.");
        }
        properties.changeManagement(id, request.version(), agency);
        return recordProperty(actor, organizationId, previous, "PROPERTY_MANAGEMENT_CHANGED");
    }

    public List<PropertyUnit> units(AuthenticatedUser actor, UUID organizationId, UUID propertyId,
            UnitAvailability availability, boolean includeArchived, int limit, int offset) {
        access(actor, organizationId, propertyId, false);
        OrganizationService.validatePage(limit, offset);
        return properties.units(propertyId, availability, includeArchived, limit, offset);
    }

    public PropertyUnit unit(AuthenticatedUser actor, UUID organizationId, UUID propertyId, UUID id) {
        access(actor, organizationId, propertyId, false);
        return findUnit(propertyId, id);
    }

    @Transactional
    public PropertyUnit createUnit(AuthenticatedUser actor, UUID organizationId, UUID propertyId, UnitDetails details) {
        requireOpen(access(actor, organizationId, propertyId, true));
        validate(details);
        UUID id = properties.createUnit(propertyId, details);
        PropertyUnit result = findUnit(propertyId, id);
        audit.recordSnapshot(actor.id(), organizationId, "UNIT_CREATED", id, null, result);
        return result;
    }

    @Transactional
    public PropertyUnit updateUnit(AuthenticatedUser actor, UUID organizationId, UUID propertyId, UUID id, UpdateUnit request) {
        requireOpen(access(actor, organizationId, propertyId, true));
        PropertyUnit previous = findUnit(propertyId, id);
        if (previous.status() == UnitStatus.ARCHIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Restore the unit before editing it.");
        }
        checkVersion(previous.version(), request.version());
        validate(request.details());
        properties.updateUnit(propertyId, id, request.version(), request.details());
        return recordUnit(actor, organizationId, previous, "UNIT_UPDATED");
    }

    @Transactional
    public PropertyUnit changeUnitStatus(AuthenticatedUser actor, UUID organizationId, UUID propertyId, UUID id, ChangeUnitStatus request) {
        requireOpen(access(actor, organizationId, propertyId, true));
        PropertyUnit previous = findUnit(propertyId, id);
        checkVersion(previous.version(), request.version());
        properties.changeUnitStatus(propertyId, id, request.version(), request.status());
        return recordUnit(actor, organizationId, previous, "UNIT_STATUS_CHANGED");
    }

    private Property access(AuthenticatedUser actor, UUID organizationId, UUID id, boolean write) {
        // Lock ordering matches organization membership mutations; check memberships after acquiring the lock.
        if (write) memberships.lock(organizationId);
        Organization organization = organizations.get(actor, organizationId);
        Property result = properties.findInOrganization(organizationId, id, write).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Property not found."));
        // Delegations can be revoked while a writer waits for the property lock. The scoped query
        // rechecks its predicate against the latest row, so revoked agencies cannot finish a new mutation.
        if (write) requireManager(organization);
        return result;
    }

    private Property recordProperty(AuthenticatedUser actor, UUID organizationId, Property previous, String action) {
        Property result = get(actor, organizationId, previous.id());
        audit.recordSnapshot(actor.id(), organizationId, action, previous.id(), previous, result);
        return result;
    }

    private PropertyUnit recordUnit(AuthenticatedUser actor, UUID organizationId, PropertyUnit previous, String action) {
        PropertyUnit result = findUnit(previous.propertyId(), previous.id());
        audit.recordSnapshot(actor.id(), organizationId, action, previous.id(), previous, result);
        return result;
    }

    private PropertyUnit findUnit(UUID propertyId, UUID id) {
        return properties.unit(propertyId, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unit not found."));
    }

    private static void requireManager(Organization organization) {
        if (organization.role() == MembershipRole.MEMBER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Organization management permission is required.");
        }
    }

    private static void requireOwner(Property property, UUID organizationId) {
        if (!property.ownerOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the owning organization can change property status or management.");
        }
    }

    private static void requireOpen(Property property) {
        if (property.status() == PropertyStatus.ARCHIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Restore the property before changing it or its units.");
        }
    }

    private static void checkVersion(long actual, long expected) {
        if (actual != expected) throw new ApiException(HttpStatus.CONFLICT, "The record has changed. Reload it before saving.");
    }

    private static void validate(PropertyDetails details) {
        if (!COUNTRIES.contains(details.countryCode().toUpperCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use a valid ISO country code.");
        }
        if ((details.latitude() == null) != (details.longitude() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Supply both latitude and longitude, or neither.");
        }
    }

    private static void validate(UnitDetails details) {
        try { Currency.getInstance(details.currency().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "Use a valid ISO currency code."); }
    }
}
