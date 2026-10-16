package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record InspectionPolicy(UUID leaseId, boolean moveInRequired, Instant enabledAt) {}
