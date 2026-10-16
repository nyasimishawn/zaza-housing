package com.zaza.housing.dto;

import java.util.List;

public record LeaseCheckoutResponse(
    boolean inspectionTrackingEnabled,
    boolean moveInRequired,
    boolean moveInAccepted,
    boolean moveOutAccepted,
    boolean inspectionsReady,
    boolean billingReady,
    boolean maintenanceReady,
    boolean leaseClosed,
    boolean unitReleased,
    boolean readyToRelease,
    List<String> blockingSteps) {}
