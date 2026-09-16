variable "monitoring_enabled" {
  type        = bool
  default     = false
  description = "Enable after DNS and managed TLS are ready; requires verified notification channels."
}
variable "notification_channels" {
  type        = list(string)
  default     = []
  description = "Existing verified Cloud Monitoring channel resource names. No recipient credentials in Terraform."
  validation {
    condition     = !var.monitoring_enabled || length(var.notification_channels) > 0
    error_message = "Monitoring must have at least one verified notification channel."
  }
}
resource "google_project_service" "monitoring" {
  count              = var.monitoring_enabled ? 1 : 0
  service            = "monitoring.googleapis.com"
  disable_on_destroy = false
}
resource "google_monitoring_uptime_check_config" "be" {
  count        = var.monitoring_enabled ? 1 : 0
  display_name = "${var.name}-https-health"
  timeout      = "10s"
  period       = "60s"
  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project_id
      host       = var.domain_name
    }
  }
  http_check {
    path         = "/actuator/health"
    port         = 443
    use_ssl      = true
    validate_ssl = true
  }
  depends_on = [google_project_service.monitoring]
}
resource "google_monitoring_alert_policy" "be" {
  count                 = var.monitoring_enabled ? 1 : 0
  display_name          = "${var.name}-unavailable"
  combiner              = "OR"
  notification_channels = var.notification_channels
  conditions {
    display_name = "HTTPS health failing for five minutes"
    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND resource.type=\"uptime_url\" AND metric.label.check_id=\"${google_monitoring_uptime_check_config.be[0].uptime_check_id}\""
      duration        = "300s"
      comparison      = "COMPARISON_LT"
      threshold_value = 1
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_FRACTION_TRUE"
      }
      trigger { count = 1 }
    }
  }
  documentation {
    mime_type = "text/markdown"
    content   = "Check DNS/TLS, LB health, BE service and DB/MCP dependencies. Follow infra/terraform/gcp-be/README.md. Do not export request bodies or secret values."
  }
}
