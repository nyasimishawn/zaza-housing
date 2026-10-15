package com.zaza.housing.service;

import com.zaza.housing.dto.LeaseConsent;
import com.zaza.housing.dto.LeaseParties;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.util.AgreementDocuments;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LeaseSignatureService {
    private final AuthService auth;
    public LeaseSignatureService(AuthService auth) { this.auth=auth; }
    public String confirm(AuthenticatedUser actor,LeaseParty party,LeaseParties parties,String hash,LeaseConsent evidence) {
        if(!Boolean.TRUE.equals(evidence.consent())) throw new ApiException(HttpStatus.BAD_REQUEST,"Explicit signing consent is required.");
        if(!hash.equals(evidence.documentHash())) throw LeaseAccessService.conflict("Agreement digest differs. Download and review the current document before signing.");
        auth.reauthenticate(actor,evidence.password());
        String name=AgreementDocuments.line(evidence.typedName());
        String expected=party==LeaseParty.TENANT?parties.tenantName():auth.me(actor).displayName();
        if(!name.equalsIgnoreCase(AgreementDocuments.line(expected))) throw new ApiException(HttpStatus.BAD_REQUEST,"Typed name must match the tenant's agreement name or the owner's account display name.");
        return name;
    }
}
