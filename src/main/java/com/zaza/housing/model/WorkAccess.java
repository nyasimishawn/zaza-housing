package com.zaza.housing.model;

import com.zaza.housing.enums.WorkParty;
import java.util.UUID;

public record WorkAccess(WorkRequest work, WorkParty party, UUID organizationId) {}
