package com.timingjeju.api.application.trip;

import java.util.Optional;
import java.util.UUID;

/** 승인된 장소 publication에서 제주공항을 확정한다. 클라이언트 이름이나 좌표는 받지 않는다. */
public interface TripAirportResolver {
  Optional<UUID> findApproved();
}
