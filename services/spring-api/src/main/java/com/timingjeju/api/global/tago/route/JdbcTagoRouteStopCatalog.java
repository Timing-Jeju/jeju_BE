package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRouteStopCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTagoRouteStopCatalog implements TagoRouteStopCatalog {
  private final JdbcTemplate jdbc;

  public JdbcTagoRouteStopCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void requireExisting(String provider, String service, String city, Set<String> nodeIds) {
    List<String> nodes = List.copyOf(nodeIds);
    int matched = 0;
    for (int offset = 0; offset < nodes.size(); offset += 500) {
      List<String> chunk = nodes.subList(offset, Math.min(offset + 500, nodes.size()));
      List<Object> parameters = new ArrayList<>();
      parameters.add(provider);
      parameters.add(service);
      parameters.add(city);
      parameters.addAll(chunk);
      Integer count =
          jdbc.queryForObject(
              "select count(*) from public.bus_stops where source_provider = ? and source_service = ? and city_code = ? and stale = false and tombstoned_at is null and node_id in ("
                  + String.join(",", Collections.nCopies(chunk.size(), "?"))
                  + ")",
              Integer.class,
              parameters.toArray());
      matched += count == null ? 0 : count;
    }
    if (matched != nodeIds.size()) throw TagoRouteImportException.stopScopeMismatch();
  }
}
