package com.timingjeju.api.global.staypolicy;

import com.timingjeju.api.application.staypolicy.StayPolicyImportResult;
import com.timingjeju.api.application.staypolicy.StayPolicyImportService;
import com.timingjeju.api.application.staypolicy.StayPolicyPayload;

public final class StayPolicyCsvImportCommand {

  private final StayPolicyImportService importService;

  public StayPolicyCsvImportCommand(StayPolicyImportService importService) {
    this.importService = importService;
  }

  public StayPolicyImportResult execute(StayPolicyImportOptions options) {
    var policies = new StayPolicyCsvReader(options.importRoot()).read(options.importFile());
    return importService.importPolicy(
        new StayPolicyPayload(
            options.version(), options.effectiveAt(), options.expectedActiveVersion(), policies),
        options.dryRun());
  }
}
