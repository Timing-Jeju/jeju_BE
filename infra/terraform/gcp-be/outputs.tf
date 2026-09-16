output "network_self_link" { value = google_compute_network.be.self_link }
output "subnet_cidr" { value = google_compute_subnetwork.be.ip_cidr_range }
output "region" { value = var.region }
output "api_ip_address" { value = google_compute_global_address.be.address }
output "egress_ip_address" { value = google_compute_address.egress.address }
output "runtime_service_account" { value = google_service_account.be.email }
output "artifact_registry_repository" { value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.be.repository_id}" }
output "private_vm_ip" { value = try(google_compute_instance.be[0].network_interface[0].network_ip, null) }
