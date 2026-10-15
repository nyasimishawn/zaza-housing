package com.zaza.housing.dto;


public record ConversationReportEvidenceResponse(
    ConversationReportResponse report, ConversationMessageResponse reportedMessage) {}
