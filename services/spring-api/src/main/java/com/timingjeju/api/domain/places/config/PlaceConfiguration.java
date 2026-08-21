package com.timingjeju.api.domain.places.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaceNearbyStopsProperties.class)
public class PlaceConfiguration {}
