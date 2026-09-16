resource "google_compute_ha_vpn_gateway" "aws" {
  name    = "${var.name}-aws"
  network = var.network_self_link
  region  = var.region
}

resource "google_compute_router" "aws" {
  name    = "${var.name}-aws"
  network = var.network_self_link
  region  = var.region
  bgp {
    asn            = var.gcp_asn
    advertise_mode = "CUSTOM"
    advertised_ip_ranges {
      range = var.be_subnet_cidr
    }
  }
}

resource "google_compute_external_vpn_gateway" "aws" {
  count           = length(var.tunnels) == 0 ? 0 : 1
  name            = "${var.name}-aws-peer"
  redundancy_type = "FOUR_IPS_REDUNDANCY"
  dynamic "interface" {
    for_each = var.tunnels
    content {
      id         = tonumber(interface.key)
      ip_address = interface.value.aws_public_ip
    }
  }
}

resource "google_compute_vpn_tunnel" "aws" {
  for_each                        = var.tunnels
  name                            = "${var.name}-aws-${each.key}"
  region                          = var.region
  vpn_gateway                     = google_compute_ha_vpn_gateway.aws.id
  vpn_gateway_interface           = floor(tonumber(each.key) / 2)
  peer_external_gateway           = google_compute_external_vpn_gateway.aws[0].id
  peer_external_gateway_interface = tonumber(each.key)
  router                          = google_compute_router.aws.id
  ike_version                     = 2
  shared_secret_wo                = var.tunnel_psks[each.key]
  shared_secret_wo_version        = each.value.secret_version
}

resource "google_compute_router_interface" "aws" {
  for_each   = var.tunnels
  name       = "${var.name}-aws-${each.key}"
  region     = var.region
  router     = google_compute_router.aws.name
  ip_range   = each.value.gcp_bgp_cidr
  vpn_tunnel = google_compute_vpn_tunnel.aws[each.key].name
}

resource "google_compute_router_peer" "aws" {
  for_each                  = var.tunnels
  name                      = "${var.name}-aws-${each.key}"
  region                    = var.region
  router                    = google_compute_router.aws.name
  interface                 = google_compute_router_interface.aws[each.key].name
  peer_ip_address           = each.value.aws_bgp_ip
  peer_asn                  = var.aws_asn
  advertised_route_priority = 100
}
