package com.zaza.housing.model;

public record ServiceFinanceAccess(
    ServicePaymentAccount account,
    WorkRequest work,
    boolean payer,
    boolean provider,
    boolean operator) {}
