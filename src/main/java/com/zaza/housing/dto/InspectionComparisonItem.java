package com.zaza.housing.dto;

public record InspectionComparisonItem(
    String key, InspectionItem moveIn, InspectionItem moveOut, boolean changed) {}
