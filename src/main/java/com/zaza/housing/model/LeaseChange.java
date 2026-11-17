package com.zaza.housing.model;
import com.zaza.housing.dto.LeaseChangeResponse;
import java.util.UUID;
public record LeaseChange(UUID leaseId, UUID revisionId, UUID requesterId, String document, LeaseChangeResponse details) { }
