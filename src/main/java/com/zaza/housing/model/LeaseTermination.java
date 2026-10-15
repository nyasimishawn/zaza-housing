package com.zaza.housing.model;

import com.zaza.housing.dto.LeaseTerminationResponse;
import java.util.UUID;

public record LeaseTermination(UUID leaseId, UUID revisionId, UUID requesterId, String document, LeaseTerminationResponse details) { }
