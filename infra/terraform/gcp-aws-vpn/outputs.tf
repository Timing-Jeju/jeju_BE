output "gateway_interfaces" {
  value       = google_compute_ha_vpn_gateway.aws.vpn_interfaces
  description = "Hand these two public interface IPs to AWS operator; one customer gateway/VPN connection per interface."
}
output "router_name" {
  value = google_compute_router.aws.name
}
output "configured_tunnels" {
  value       = keys(google_compute_vpn_tunnel.aws)
  description = "Configured is not connected: verify all BGP sessions and end-to-end TLS before BE activation."
}
