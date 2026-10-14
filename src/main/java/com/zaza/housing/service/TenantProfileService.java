package com.zaza.housing.service;

import com.zaza.housing.dto.TenantProfileDetails;
import com.zaza.housing.dto.TenantProfileResponse;
import com.zaza.housing.dto.UpdateTenantProfileRequest;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.TenantProfile;
import com.zaza.housing.repository.TenantProfileRepository;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantProfileService {
    private final TenantProfileRepository profiles;
    private final AuditService audit;
    public TenantProfileService(TenantProfileRepository profiles, AuditService audit) { this.profiles = profiles; this.audit = audit; }
    public TenantProfileResponse get(AuthenticatedUser actor) { return response(find(actor.id(), false)); }
    @Transactional
    public TenantProfileResponse create(AuthenticatedUser actor, TenantProfileDetails details) {
        TenantProfileDetails normalized = normalize(details);
        profiles.create(actor.id(), normalized);
        var result = get(actor);
        audit.recordSnapshot(actor.id(), null, "TENANT_PROFILE_CREATED", actor.id(), null, result);
        return result;
    }
    @Transactional
    public TenantProfileResponse update(AuthenticatedUser actor, UpdateTenantProfileRequest request) {
        var previous = response(find(actor.id(), true));
        profiles.update(actor.id(), request.version(), normalize(request.details()));
        var result = get(actor);
        audit.recordSnapshot(actor.id(), null, "TENANT_PROFILE_UPDATED", actor.id(), previous, result);
        return result;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public TenantProfile forApplication(UUID user) {
        return profiles.find(user, true).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Complete your tenant profile before applying."));
    }
    private TenantProfile find(UUID user, boolean lock) {
        return profiles.find(user, lock).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tenant profile not found."));
    }
    private static TenantProfileResponse response(TenantProfile profile) {
        return new TenantProfileResponse(profile.details(), profile.version(), profile.createdAt(), profile.updatedAt());
    }
    private static TenantProfileDetails normalize(TenantProfileDetails details) {
        String country = details.countryCode().toUpperCase(Locale.ROOT);
        if (!Arrays.asList(Locale.getISOCountries()).contains(country)) throw invalid("Use a valid ISO country code.");
        if ((details.monthlyIncome() == null) != (details.incomeCurrency() == null)) throw invalid("Supply income and its currency together, or omit both.");
        String currency = details.incomeCurrency() == null ? null : details.incomeCurrency().toUpperCase(Locale.ROOT);
        if (currency != null) {
            try { Currency.getInstance(currency); }
            catch (IllegalArgumentException error) { throw invalid("Use a valid ISO income currency."); }
        }
        return new TenantProfileDetails(details.legalName().trim(), details.phone(), country, details.city().trim(),
                details.employmentStatus(), details.monthlyIncome(), currency);
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
