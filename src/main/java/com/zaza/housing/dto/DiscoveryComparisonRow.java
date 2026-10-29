package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryComparisonRow(UUID id, boolean available, DiscoveryItemResponse item) {}
