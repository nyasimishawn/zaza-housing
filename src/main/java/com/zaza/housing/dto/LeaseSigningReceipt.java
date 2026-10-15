package com.zaza.housing.dto;

import java.util.List;
import java.util.UUID;

public record LeaseSigningReceipt(UUID leaseId, LeaseRevisionResponse revision, List<LeaseSignatureResponse> signatures, String hashAlgorithm) { }
