package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkStatus;
import java.util.UUID;

public record WorkDecisionResponse(UUID id, long version, WorkStatus status) implements WorkView {}
