package com.zaza.housing.service;

import com.zaza.housing.repository.WorkRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WorkCheckoutGuard {
  private final WorkRequestRepository requests;

  public WorkCheckoutGuard(WorkRequestRepository requests) {
    this.requests = requests;
  }

  public void check(UUID lease) {
    if (requests.blocksCheckout(lease))
      throw WorkAccessService.conflict(
          "Resolve started maintenance, completion approvals and maintenance disputes before"
              + " releasing this unit.");
  }
}
