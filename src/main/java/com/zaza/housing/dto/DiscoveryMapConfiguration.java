package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryMapConfiguration(
    String tileTemplate,
    String attribution,
    String attributionUrl,
    int minZoom,
    int maxZoom,
    String placeSource) {}
