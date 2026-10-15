package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkStatus;
import java.util.UUID;

public sealed interface WorkView
    permits WorkRequestResponse, ProviderJobResponse, WorkDecisionResponse {
  UUID id();

  long version();

  WorkStatus status();
}
