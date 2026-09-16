mock_provider "google" {}

run "reject_monitoring_without_recipient" {
  command = plan
  variables { monitoring_enabled = true }
  expect_failures = [var.notification_channels]
}

run "https_monitoring" {
  command = plan
  variables {
    monitoring_enabled    = true
    notification_channels = ["projects/example-project/notificationChannels/123"]
  }
  assert {
    condition     = google_monitoring_uptime_check_config.be[0].http_check[0].validate_ssl && length(google_monitoring_alert_policy.be[0].notification_channels) == 1
    error_message = "TLS 검증과 실제 수신 채널 연결을 유지해야 합니다."
  }
}

run "reject_public_subnet" {
  command = plan
  variables {
    subnet_cidr = "8.8.8.0/24"
  }
  expect_failures = [var.subnet_cidr]
}

run "reject_wrong_region_zone" {
  command = plan
  variables {
    zone = "us-central1-a"
  }
  expect_failures = [var.zone]
}

variables {
  project_id  = "example-project"
  domain_name = "api.example.com"
}

run "foundation_only" {
  command = plan
  assert {
    condition     = length(google_compute_instance.be) == 0
    error_message = "Bootstrap must not create a VM before image and secrets are provisioned."
  }
  assert {
    condition     = google_compute_network.be.auto_create_subnetworks == false
    error_message = "The BE requires its own private VPC."
  }
  assert {
    condition     = google_compute_backend_service.be.log_config[0].enable == false
    error_message = "Request logs must not expose user inputs."
  }
}

run "reject_unpinned_image" {
  command = plan
  variables {
    runtime_enabled = true
    image           = "asia-northeast3-docker.pkg.dev/example-project/timing-jeju-be/api:latest"
  }
  expect_failures = [google_compute_instance.be]
}

run "runtime_plan" {
  command = plan
  variables {
    runtime_enabled = true
    image           = "asia-northeast3-docker.pkg.dev/example-project/timing-jeju-be/api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
  assert {
    condition     = length(google_compute_instance.be[0].network_interface[0].access_config) == 0
    error_message = "VM must have no public IP."
  }
}
