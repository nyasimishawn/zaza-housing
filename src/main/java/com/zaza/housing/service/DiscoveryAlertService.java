package com.zaza.housing.service;

import com.zaza.housing.dto.DiscoveryQuery;
import com.zaza.housing.repository.*;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiscoveryAlertService {
  private final SavedDiscoveryRepository saved;
  private final DiscoveryRepository discovery;
  private final DiscoveryQueryService queries;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final boolean enabled;

  public DiscoveryAlertService(
      SavedDiscoveryRepository saved,
      DiscoveryRepository discovery,
      DiscoveryQueryService queries,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager manager,
      @Value("${zaza.discovery.alerts-enabled:true}") boolean enabled) {
    this.saved = saved;
    this.discovery = discovery;
    this.queries = queries;
    this.json = json;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${zaza.discovery.alert-poll-ms:60000}")
  public void poll() {
    if (!enabled) return;
    for (UUID id : saved.due(clock.instant()))
      try {
        scan(id);
      } catch (RuntimeException error) {
        transactions.executeWithoutResult(tx -> saved.failed(id, clock.instant().plusSeconds(300)));
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Discovery alert scan failed for {}", id);
      }
  }

  public void scan(UUID id) {
    transactions.executeWithoutResult(
        tx -> {
          var row = saved.lockDue(id, clock.instant());
          if (row == null) return;
          var q =
              queries.normalize(
                  json.readValue(row.get("query").toString(), DiscoveryQuery.class), true);
          UUID generation = (UUID) row.get("generation"), user = (UUID) row.get("user_id");
          for (UUID target : discovery.unseen(id, generation, q))
            saved.alert(id, user, generation, target, clock.instant());
          saved.scanned(
              id,
              SavedDiscoveryService.next((String) row.get("frequency"), clock.instant()),
              clock.instant());
        });
  }
}
