package com.zaza.housing.dto;
import java.math.BigDecimal;
import java.util.UUID;
public record PropertyInterestResponse(UUID organizationId,String organizationName,BigDecimal sharePercent,boolean controllingOwner) { }
