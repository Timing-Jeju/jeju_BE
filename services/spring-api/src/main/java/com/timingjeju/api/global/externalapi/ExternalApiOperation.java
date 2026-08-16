package com.timingjeju.api.global.externalapi;

public enum ExternalApiOperation {
  TOUR_LDONG_CODE(ExternalApiProvider.TOUR_API, "kor_service_2", "ldong_code"),
  TOUR_CLASSIFICATION_CODE(ExternalApiProvider.TOUR_API, "kor_service_2", "classification_code"),
  TOUR_AREA_BASED_LIST(ExternalApiProvider.TOUR_API, "kor_service_2", "area_based_list"),
  TOUR_LOCATION_BASED_LIST(ExternalApiProvider.TOUR_API, "kor_service_2", "location_based_list"),
  TOUR_SEARCH_KEYWORD(ExternalApiProvider.TOUR_API, "kor_service_2", "search_keyword"),
  TOUR_SEARCH_STAY(ExternalApiProvider.TOUR_API, "kor_service_2", "search_stay"),
  TOUR_DETAIL_COMMON(ExternalApiProvider.TOUR_API, "kor_service_2", "detail_common"),
  TOUR_DETAIL_INTRO(ExternalApiProvider.TOUR_API, "kor_service_2", "detail_intro"),
  TOUR_DETAIL_INFO(ExternalApiProvider.TOUR_API, "kor_service_2", "detail_info"),
  TOUR_DETAIL_IMAGE(ExternalApiProvider.TOUR_API, "kor_service_2", "detail_image"),
  TOUR_AREA_SYNC(ExternalApiProvider.TOUR_API, "kor_service_2", "area_sync"),
  TAGO_CITY_CODE(ExternalApiProvider.TAGO, "reference_code", "city_code"),
  TAGO_STATION_LIST(ExternalApiProvider.TAGO, "bus_stop", "station_list"),
  TAGO_NEARBY_STOP(ExternalApiProvider.TAGO, "bus_stop", "nearby_stop"),
  TAGO_ROUTE_INFO(ExternalApiProvider.TAGO, "bus_route", "route_info"),
  TAGO_ROUTE_STOPS(ExternalApiProvider.TAGO, "bus_route", "route_stops"),
  TAGO_ARRIVAL(ExternalApiProvider.TAGO, "bus_arrival", "arrival"),
  TMAP_DRIVING_ROUTE(ExternalApiProvider.TMAP, "mobility_route", "driving_route"),
  TMAP_PEDESTRIAN_ROUTE(ExternalApiProvider.TMAP, "mobility_route", "pedestrian_route"),
  TMAP_TRANSIT_ROUTE(ExternalApiProvider.TMAP, "mobility_route", "transit_route"),
  KMA_ULTRA_CURRENT(ExternalApiProvider.KMA, "village_forecast", "ultra_current"),
  KMA_ULTRA_FORECAST(ExternalApiProvider.KMA, "village_forecast", "ultra_forecast"),
  KMA_VILLAGE_FORECAST(ExternalApiProvider.KMA, "village_forecast", "village_forecast"),
  KMA_FORECAST_VERSION(ExternalApiProvider.KMA, "village_forecast", "forecast_version");

  private final ExternalApiProvider provider;
  private final String serviceTag;
  private final String operationTag;

  ExternalApiOperation(ExternalApiProvider provider, String serviceTag, String operationTag) {
    this.provider = provider;
    this.serviceTag = serviceTag;
    this.operationTag = operationTag;
  }

  public ExternalApiProvider provider() {
    return provider;
  }

  String serviceTag() {
    return serviceTag;
  }

  String operationTag() {
    return operationTag;
  }
}
