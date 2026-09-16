variable "project_id" {
  type = string
}
variable "region" {
  type    = string
  default = "asia-northeast3"
}
variable "name" {
  type    = string
  default = "timing-jeju-be"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,38}[a-z0-9]$", var.name))
    error_message = "Use a lowercase resource prefix, 2–40 characters."
  }
}
variable "network_self_link" {
  type        = string
  description = "Existing GCP BE VPC self_link. No AWS or default network is modified."
}
variable "be_subnet_cidr" {
  type = string
  validation {
    condition = can(cidrnetmask(var.be_subnet_cidr)) && try(
      (startswith(cidrhost(var.be_subnet_cidr, 0), "10.") && tonumber(split("/", var.be_subnet_cidr)[1]) >= 8) ||
      (startswith(cidrhost(var.be_subnet_cidr, 0), "192.168.") && tonumber(split("/", var.be_subnet_cidr)[1]) >= 16) ||
    (can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", cidrhost(var.be_subnet_cidr, 0))) && tonumber(split("/", var.be_subnet_cidr)[1]) >= 12), false)
    error_message = "Provide the BE IPv4 subnet, never a default route."
  }
}
variable "gcp_asn" {
  type    = number
  default = 64514
  validation {
    condition     = var.gcp_asn >= 64512 && var.gcp_asn <= 65534
    error_message = "Use an unoccupied private 16-bit ASN."
  }
}
variable "aws_asn" {
  type    = number
  default = 64512
  validation {
    condition     = var.aws_asn >= 64512 && var.aws_asn <= 65534 && var.aws_asn != var.gcp_asn
    error_message = "Use a private AWS ASN different from the GCP ASN."
  }
}
variable "tunnels" {
  description = "Empty creates gateway/router only. Supply all four AWS tunnel endpoints together after AWS provisioning. Keys must be 0,1,2,3: two tunnels per GCP interface."
  type = map(object({
    aws_public_ip  = string
    gcp_bgp_cidr   = string
    aws_bgp_ip     = string
    secret_version = number
  }))
  default = {}
  validation {
    condition = length(var.tunnels) == 0 || (
      toset(keys(var.tunnels)) == toset(["0", "1", "2", "3"]) &&
      length(distinct([for t in values(var.tunnels) : t.aws_public_ip])) == 4 &&
      length(distinct([for t in values(var.tunnels) : try(cidrhost(t.gcp_bgp_cidr, 0), "invalid")])) == 4 &&
      alltrue([for t in values(var.tunnels) :
        can(cidrnetmask("${t.aws_public_ip}/32")) &&
        can(regex("^169\\.254\\.[0-9]+\\.[0-9]+/30$", t.gcp_bgp_cidr)) &&
        can(regex("^169\\.254\\.[0-9]+\\.[0-9]+$", t.aws_bgp_ip)) &&
        try(contains([cidrhost(t.gcp_bgp_cidr, 1), cidrhost(t.gcp_bgp_cidr, 2)], t.aws_bgp_ip), false) &&
        try(contains([cidrhost(t.gcp_bgp_cidr, 1), cidrhost(t.gcp_bgp_cidr, 2)], split("/", t.gcp_bgp_cidr)[0]), false) &&
        split("/", t.gcp_bgp_cidr)[0] != t.aws_bgp_ip &&
        t.secret_version >= 1 && floor(t.secret_version) == t.secret_version
      ])
    )
    error_message = "Configure zero or four distinct IPv4 endpoints, AWS-provided link-local /30 BGP ranges and positive PSK versions."
  }
}
variable "tunnel_psks" {
  type        = map(string)
  ephemeral   = true
  sensitive   = true
  default     = {}
  description = "Inject via TF_VAR_tunnel_psks at execution; never put in tfvars. Used only by shared_secret_wo, absent from plan/state."
  validation {
    condition     = alltrue([for psk in values(var.tunnel_psks) : can(regex("^[A-Za-z1-9][A-Za-z0-9._]{7,63}$", psk))])
    error_message = "PSKs must satisfy the AWS 8–64 character restrictions."
  }
}
