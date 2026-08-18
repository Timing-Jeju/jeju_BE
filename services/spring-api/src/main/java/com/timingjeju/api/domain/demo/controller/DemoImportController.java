package com.timingjeju.api.domain.demo.controller;

import com.timingjeju.api.application.demo.DemoImportResult;
import com.timingjeju.api.application.demo.DemoStorageView;
import com.timingjeju.api.domain.demo.service.DemoImportService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/demo")
public class DemoImportController {
  private final DemoImportService service;

  public DemoImportController(DemoImportService service) {
    this.service = service;
  }

  @PostMapping("/imports/tour-api")
  public DemoImportResult importTourApi() {
    return service.importTourPlaces();
  }

  @GetMapping("/storage")
  public DemoStorageView storage() {
    return service.latestStorage();
  }

  @GetMapping(value = "/storage/view", produces = MediaType.TEXT_HTML_VALUE)
  public String storageView() {
    return service.storageView();
  }
}
