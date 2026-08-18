package com.timingjeju.api.application.demo;

import java.util.List;

public record DemoStorageView(
    List<DemoRunRow> runs,
    List<DemoSnapshotRow> snapshots,
    List<DemoPlaceRow> places,
    List<DemoPlaceDetailRow> placeDetails,
    List<DemoPlaceDetailItemRow> detailItems,
    List<DemoPlaceImageRow> placeImages,
    List<DemoProvenanceRow> provenances,
    long totalRuns,
    long totalSnapshots,
    long totalPlaces,
    long totalPlaceDetails,
    long totalDetailItems,
    long totalPlaceImages,
    long totalProvenances) {
  public DemoStorageView {
    runs = List.copyOf(runs);
    snapshots = List.copyOf(snapshots);
    places = List.copyOf(places);
    placeDetails = List.copyOf(placeDetails);
    detailItems = List.copyOf(detailItems);
    placeImages = List.copyOf(placeImages);
    provenances = List.copyOf(provenances);
  }

  public List<String> tableFlow() {
    return List.of(
        "data_import_runs",
        "external_api_snapshots",
        "tour_places",
        "place_details",
        "place_detail_items",
        "place_images",
        "tour_api_operation_provenance");
  }
}
