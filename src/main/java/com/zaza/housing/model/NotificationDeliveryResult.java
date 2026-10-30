package com.zaza.housing.model;

/** ACCEPTED means the provider accepted the request; it does not assert handset delivery. */
public record NotificationDeliveryResult(
    String status, String errorCode, String reference, boolean terminal) {
  public static NotificationDeliveryResult accepted(String reference) {
    return new NotificationDeliveryResult("ACCEPTED", null, reference, true);
  }

  public static NotificationDeliveryResult unavailable() {
    return new NotificationDeliveryResult("PENDING", "CHANNEL_UNAVAILABLE", null, false);
  }
}
