package com.timingjeju.api.application.importing;

public final class ImportRunLifecycleException extends RuntimeException {

  private final ImportRunLifecycleError code;

  private ImportRunLifecycleException(ImportRunLifecycleError code) {
    super(null, null, false, false);
    this.code = code;
  }

  public static ImportRunLifecycleException of(ImportRunLifecycleError code) {
    return new ImportRunLifecycleException(code);
  }

  public ImportRunLifecycleError code() {
    return code;
  }
}
