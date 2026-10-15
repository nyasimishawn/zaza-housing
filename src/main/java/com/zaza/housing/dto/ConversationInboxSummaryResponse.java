package com.zaza.housing.dto;


public record ConversationInboxSummaryResponse(
    long open, long closed, long unreadConversations, long unreadMessages) {}
