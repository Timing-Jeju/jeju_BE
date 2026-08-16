package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.importing.ImportRunLease;

public interface TagoStopImportSession {
  StartedTagoStopImport start(TagoStopImportCommand command);

  void fail(ImportRunLease lease);
}
