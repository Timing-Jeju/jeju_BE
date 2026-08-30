package com.timingjeju.api.domain.savedplaces.model;

import java.util.List;

public record SavedPlacesListResult(
    List<SavedPlace> items, int size, boolean hasNext, String nextCursor) {}
