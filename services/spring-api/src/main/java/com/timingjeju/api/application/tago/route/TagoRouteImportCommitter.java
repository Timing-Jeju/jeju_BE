package com.timingjeju.api.application.tago.route;

public interface TagoRouteImportCommitter {
  TagoRouteCommitResult commit(TagoRouteCommitCommand command);
}
