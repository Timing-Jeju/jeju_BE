package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.importing.ImportRunLease;

public interface TagoRouteImportSession {
  StartedTagoRouteImport start(TagoRouteImportCommand command);

  void fail(ImportRunLease lease);
}
