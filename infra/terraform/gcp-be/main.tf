locals {
  services   = toset(["compute.googleapis.com", "artifactregistry.googleapis.com", "secretmanager.googleapis.com", "iam.googleapis.com", "iap.googleapis.com"])
  secret_ids = toset(concat([var.runtime_env_secret], [for item in values(var.secret_files) : item.secret_id]))
}

resource "google_project_service" "required" {
  for_each           = local.services
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}
resource "google_compute_network" "be" {
  name                    = var.name
  auto_create_subnetworks = false
  routing_mode            = "GLOBAL"
  depends_on              = [google_project_service.required]
}
resource "google_compute_subnetwork" "be" {
  name                     = "${var.name}-private"
  region                   = var.region
  network                  = google_compute_network.be.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true
}
resource "google_compute_router" "nat" {
  name    = "${var.name}-nat"
  region  = var.region
  network = google_compute_network.be.id
}
resource "google_compute_address" "egress" {
  name       = "${var.name}-egress"
  region     = var.region
  depends_on = [google_project_service.required]
}
resource "google_compute_router_nat" "be" {
  name                               = "${var.name}-nat"
  region                             = var.region
  router                             = google_compute_router.nat.name
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = [google_compute_address.egress.self_link]
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
  subnetwork {
    name                    = google_compute_subnetwork.be.id
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }
}
resource "google_service_account" "be" {
  account_id   = var.name
  display_name = "Timing Jeju BE runtime"
  depends_on   = [google_project_service.required]
}
resource "google_artifact_registry_repository" "be" {
  repository_id = var.name
  location      = var.region
  format        = "DOCKER"
  depends_on    = [google_project_service.required]
}
resource "google_artifact_registry_repository_iam_member" "reader" {
  location   = google_artifact_registry_repository.be.location
  repository = google_artifact_registry_repository.be.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.be.email}"
}
resource "google_secret_manager_secret_iam_member" "reader" {
  for_each   = local.secret_ids
  project    = var.project_id
  secret_id  = each.value
  role       = "roles/secretmanager.secretAccessor"
  member     = "serviceAccount:${google_service_account.be.email}"
  depends_on = [google_project_service.required, google_secret_manager_secret.runtime]
}
resource "google_secret_manager_secret" "runtime" {
  for_each  = var.create_secret_containers ? local.secret_ids : toset([])
  secret_id = each.value
  replication {
    auto {}
  }
  deletion_protection = true
  depends_on          = [google_project_service.required]
}
resource "google_compute_firewall" "load_balancer" {
  name                    = "${var.name}-lb"
  network                 = google_compute_network.be.id
  source_ranges           = ["35.191.0.0/16", "130.211.0.0/22"]
  target_service_accounts = [google_service_account.be.email]
  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }
}
resource "google_compute_firewall" "iap" {
  count                   = var.enable_iap_ssh ? 1 : 0
  name                    = "${var.name}-iap"
  network                 = google_compute_network.be.id
  source_ranges           = ["35.235.240.0/20"]
  target_service_accounts = [google_service_account.be.email]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}
resource "google_compute_instance" "be" {
  count               = var.runtime_enabled ? 1 : 0
  name                = var.name
  zone                = var.zone
  machine_type        = var.machine_type
  deletion_protection = var.deletion_protection
  boot_disk {
    initialize_params {
      image = var.boot_image
      size  = 30
      type  = "pd-balanced"
    }
  }
  network_interface { subnetwork = google_compute_subnetwork.be.id }
  service_account {
    email  = google_service_account.be.email
    scopes = ["cloud-platform"]
  }
  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }
  metadata = {
    enable-oslogin         = "TRUE"
    block-project-ssh-keys = "TRUE"
    serial-port-enable     = "FALSE"
  }
  metadata_startup_script = templatefile("${path.module}/templates/startup.sh.tftpl", {
    config = base64encode(jsonencode({
      project     = var.project_id
      registry    = "${var.region}-docker.pkg.dev"
      image       = var.image
      environment = { secret_id = var.runtime_env_secret, version = var.runtime_env_version }
      files       = var.secret_files
      hosts       = var.private_host_mappings
    }))
  })
  lifecycle {
    precondition {
      condition     = startswith(var.image, "${var.region}-docker.pkg.dev/${var.project_id}/${var.name}/") && can(regex("@sha256:[a-f0-9]{64}$", var.image))
      error_message = "Runtime requires a digest-pinned image from the dedicated Artifact Registry repository."
    }
  }
  depends_on = [google_compute_router_nat.be, google_secret_manager_secret_iam_member.reader, google_artifact_registry_repository_iam_member.reader]
}
resource "google_compute_instance_group" "be" {
  name       = var.name
  zone       = var.zone
  instances  = google_compute_instance.be[*].self_link
  depends_on = [google_project_service.required]
  named_port {
    name = "http"
    port = 8080
  }
}
resource "google_compute_health_check" "be" {
  name       = var.name
  depends_on = [google_project_service.required]
  http_health_check {
    port         = 8080
    request_path = "/actuator/health"
  }
}
resource "google_compute_backend_service" "be" {
  name                            = var.name
  protocol                        = "HTTP"
  port_name                       = "http"
  load_balancing_scheme           = "EXTERNAL_MANAGED"
  health_checks                   = [google_compute_health_check.be.id]
  timeout_sec                     = 180
  connection_draining_timeout_sec = 210
  backend { group = google_compute_instance_group.be.id }
  log_config { enable = false }
}
resource "google_compute_global_address" "be" {
  name       = var.name
  depends_on = [google_project_service.required]
}
resource "google_compute_managed_ssl_certificate" "be" {
  name = var.name
  managed { domains = [var.domain_name] }
  depends_on = [google_project_service.required]
}
resource "google_compute_ssl_policy" "be" {
  name            = var.name
  min_tls_version = "TLS_1_2"
  profile         = "MODERN"
  depends_on      = [google_project_service.required]
}
resource "google_compute_url_map" "be" {
  name            = var.name
  default_service = google_compute_backend_service.be.id
}
resource "google_compute_target_https_proxy" "be" {
  name             = var.name
  url_map          = google_compute_url_map.be.id
  ssl_certificates = [google_compute_managed_ssl_certificate.be.id]
  ssl_policy       = google_compute_ssl_policy.be.id
}
resource "google_compute_global_forwarding_rule" "https" {
  name                  = "${var.name}-https"
  target                = google_compute_target_https_proxy.be.id
  ip_address            = google_compute_global_address.be.id
  port_range            = "443"
  load_balancing_scheme = "EXTERNAL_MANAGED"
}
resource "google_compute_url_map" "redirect" {
  name       = "${var.name}-redirect"
  depends_on = [google_project_service.required]
  default_url_redirect {
    https_redirect         = true
    strip_query            = false
    redirect_response_code = "MOVED_PERMANENTLY_DEFAULT"
  }
}
resource "google_compute_target_http_proxy" "redirect" {
  name    = "${var.name}-redirect"
  url_map = google_compute_url_map.redirect.id
}
resource "google_compute_global_forwarding_rule" "http" {
  name                  = "${var.name}-http"
  target                = google_compute_target_http_proxy.redirect.id
  ip_address            = google_compute_global_address.be.id
  port_range            = "80"
  load_balancing_scheme = "EXTERNAL_MANAGED"
}
