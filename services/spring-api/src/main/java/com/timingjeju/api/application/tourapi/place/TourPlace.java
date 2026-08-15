package com.timingjeju.api.application.tourapi.place;

import java.time.Instant;

public record TourPlace(
    String contentId,
    String contentTypeId,
    String title,
    double longitude,
    double latitude,
    String address,
    String addressDetail,
    String imageUrl,
    String thumbnailUrl,
    String lDongRegnCd,
    String lDongSignguCd,
    String lclsSystm1,
    String lclsSystm2,
    String lclsSystm3,
    Instant sourceModifiedAt) {}
