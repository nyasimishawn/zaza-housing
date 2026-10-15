package com.zaza.housing.util;

import com.zaza.housing.dto.LeaseParties;
import com.zaza.housing.dto.LeaseTerms;
import com.zaza.housing.enums.LeaseParty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

public final class AgreementDocuments {
    private AgreementDocuments() { }
    public static String line(String value) { return value.strip().replaceAll("\\s+", " "); }
    public static String lease(UUID id,int number,LeaseTerms terms,LeaseParties parties) {
        return terms.title()+"\nLease: "+id+"\nRevision: "+number+"\n"
                +"Lessor: "+parties.lessorName()+"\nTenant: "+parties.tenantName()+"\nTenant email: "+parties.tenantEmail()
                +"\nTenant phone: "+parties.tenantPhone()+"\nPremises: "+parties.propertyAddress()+", "+parties.city()+", "+parties.countryCode()
                +"\nUnit: "+parties.unitLabel()+"\nStart: "+terms.startDate()+"\nEnd: "+terms.endDate()
                +"\nMonthly rent: "+terms.currency()+" "+terms.monthlyRent().toPlainString()+"\nDeposit: "+terms.currency()+" "+terms.deposit().toPlainString()
                +"\nMonthly due day: "+terms.rentDueDay()+"\n\nAgreement text\n"+terms.agreementText()+"\n";
    }
    public static String termination(UUID lease,UUID revision,String originalHash,LeaseParty party,LocalDate date,String message) {
        return "Lease early termination proposal\nLease: "+lease+"\nAgreement revision: "+revision+"\nAgreement SHA-256: "+originalHash
                +"\nRequested by: "+party+"\nProposed end date: "+date+"\nReason: "+message.strip()+"\n";
    }
    public static String hash(String document) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
