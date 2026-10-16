package com.zaza.housing.dto;

public record DocumentStorageStatusResponse(
    boolean configured,
    String acceptedContent,
    int maximumVersionsPerDocument,
    String encryption) {}
