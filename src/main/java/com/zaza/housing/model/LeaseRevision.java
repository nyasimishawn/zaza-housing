package com.zaza.housing.model;

import com.zaza.housing.dto.LeaseParties;
import com.zaza.housing.dto.LeaseTerms;
import java.time.Instant;
import java.util.UUID;

public record LeaseRevision(UUID id, UUID leaseId, int number, LeaseTerms terms, LeaseParties parties, String document, String documentHash, boolean shared, Instant createdAt) { }
