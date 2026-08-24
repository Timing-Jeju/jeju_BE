package com.timingjeju.api.application.commandinput;

import java.util.Objects;
import java.util.UUID;

public sealed interface CoarseLocation
    permits CoarseLocation.Grid100m, CoarseLocation.Place, CoarseLocation.Stop {

  String type();

  Integer precisionMeters();

  record Grid100m(int gridX, int gridY) implements CoarseLocation {
    @Override
    public String type() {
      return "GRID_100M";
    }

    @Override
    public Integer precisionMeters() {
      return 100;
    }
  }

  record Place(UUID placeId) implements CoarseLocation {
    public Place {
      Objects.requireNonNull(placeId, "placeId는 필수입니다.");
    }

    @Override
    public String type() {
      return "PLACE";
    }

    @Override
    public Integer precisionMeters() {
      return null;
    }
  }

  record Stop(UUID stopId) implements CoarseLocation {
    public Stop {
      Objects.requireNonNull(stopId, "stopId는 필수입니다.");
    }

    @Override
    public String type() {
      return "STOP";
    }

    @Override
    public Integer precisionMeters() {
      return null;
    }
  }
}
