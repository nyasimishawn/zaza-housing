package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record LeaseRevisionResponse(UUID id, int number, LeaseTerms terms, LeaseParties parties, String documentHash, Instant createdAt) { }
