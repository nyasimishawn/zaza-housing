package com.zaza.housing.dto;

import java.util.UUID;

public record AgencyPropertyResponse(
    UUID id, String name, String city, String countryCode, String status, long version) {}
