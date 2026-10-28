package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryPlaceResponse(
    long id,
    String name,
    String countryCode,
    double latitude,
    double longitude,
    long population,
    String source) {}
