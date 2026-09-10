package com.timingjeju.api.support.postgresql;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public final class PostgreSqlLauncherSessionListener implements LauncherSessionListener {
  @Override
  public void launcherSessionClosed(LauncherSession session) {
    PostgreSqlLauncherSessionPool.closeSession();
  }
}
