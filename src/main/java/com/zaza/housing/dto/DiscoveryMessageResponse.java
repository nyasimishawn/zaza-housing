package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryMessageResponse(UUID id, String message) {}
