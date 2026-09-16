mock_provider "google" {}

variables {
  project_id        = "test-project"
  network_self_link = "projects/test-project/global/networks/test"
  be_subnet_cidr    = "10.80.0.0/24"
}

run "gateway_only" {
  command = plan
  assert {
    condition     = length(google_compute_vpn_tunnel.aws) == 0
    error_message = "AWS 정보 없이 tunnel을 생성하지 않아야 합니다."
  }
  assert {
    condition     = google_compute_router.aws.bgp[0].advertise_mode == "CUSTOM"
    error_message = "명시적인 BE subnet만 광고해야 합니다."
  }
}

run "reject_default_route" {
  command = plan
  variables {
    be_subnet_cidr = "0.0.0.0/0"
  }
  expect_failures = [var.be_subnet_cidr]
}

run "reject_partial_aws_tunnels" {
  command = plan
  variables {
    tunnels = {
      "0" = { aws_public_ip = "192.0.2.10", gcp_bgp_cidr = "169.254.10.2/30", aws_bgp_ip = "169.254.10.1", secret_version = 1 }
    }
    tunnel_psks = { "0" = "syntheticTestOnly0" }
  }
  expect_failures = [var.tunnels]
}

run "four_tunnels" {
  command = plan
  variables {
    tunnels = {
      "0" = { aws_public_ip = "192.0.2.10", gcp_bgp_cidr = "169.254.10.2/30", aws_bgp_ip = "169.254.10.1", secret_version = 1 }
      "1" = { aws_public_ip = "192.0.2.11", gcp_bgp_cidr = "169.254.11.2/30", aws_bgp_ip = "169.254.11.1", secret_version = 1 }
      "2" = { aws_public_ip = "192.0.2.12", gcp_bgp_cidr = "169.254.12.2/30", aws_bgp_ip = "169.254.12.1", secret_version = 1 }
      "3" = { aws_public_ip = "192.0.2.13", gcp_bgp_cidr = "169.254.13.2/30", aws_bgp_ip = "169.254.13.1", secret_version = 1 }
    }
    tunnel_psks = { "0" = "syntheticTestOnly0", "1" = "syntheticTestOnly1", "2" = "syntheticTestOnly2", "3" = "syntheticTestOnly3" }
  }
  assert {
    condition = (
      length(google_compute_vpn_tunnel.aws) == 4 &&
      google_compute_vpn_tunnel.aws["0"].vpn_gateway_interface == 0 &&
      google_compute_vpn_tunnel.aws["1"].vpn_gateway_interface == 0 &&
      google_compute_vpn_tunnel.aws["2"].vpn_gateway_interface == 1 &&
      google_compute_vpn_tunnel.aws["3"].vpn_gateway_interface == 1
    )
    error_message = "각 GCP interface에 AWS tunnel 두 개씩 연결해야 합니다."
  }
}
