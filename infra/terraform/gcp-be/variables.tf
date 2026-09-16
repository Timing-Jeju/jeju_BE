variable "project_id" { type = string }
variable "region" {
  type    = string
  default = "asia-northeast3"
}
variable "zone" {
  type    = string
  default = "asia-northeast3-a"
  validation {
    condition     = can(regex("^[a-z]+-[a-z]+[0-9]+-[a-z]$", var.zone)) && startswith(var.zone, "${var.region}-")
    error_message = "VM zone must belong to the subnet region."
  }
}
variable "name" {
  type    = string
  default = "timing-jeju-be"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{3,24}$", var.name))
    error_message = "Use a lowercase GCP resource prefix of 4–25 characters."
  }
}
variable "subnet_cidr" {
  type    = string
  default = "10.80.0.0/24"
  validation {
    condition = can(cidrnetmask(var.subnet_cidr)) && try(
      (startswith(cidrhost(var.subnet_cidr, 0), "10.") && tonumber(split("/", var.subnet_cidr)[1]) >= 8) ||
      (startswith(cidrhost(var.subnet_cidr, 0), "192.168.") && tonumber(split("/", var.subnet_cidr)[1]) >= 16) ||
    (can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", cidrhost(var.subnet_cidr, 0))) && tonumber(split("/", var.subnet_cidr)[1]) >= 12), false)
    error_message = "Use an RFC1918 IPv4 subnet that does not overlap AWS or Docker networks."
  }
}
variable "domain_name" {
  type = string
  validation {
    condition     = length(var.domain_name) <= 253 && can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$", var.domain_name))
    error_message = "Supply the API DNS hostname, not a URL."
  }
}
variable "runtime_enabled" {
  description = "Create the VM only after image, external DB, secrets and MCP network are ready."
  type        = bool
  default     = false
}
variable "image" {
  description = "Immutable image in this project's regional Artifact Registry repository."
  type        = string
  default     = ""
}
variable "boot_image" {
  description = "Ubuntu 24.04 image; pin a tested image self-link before production deployment."
  type        = string
  default     = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
}
variable "machine_type" {
  type    = string
  default = "e2-standard-2"
  validation {
    condition     = contains(["e2-standard-2", "e2-standard-4", "e2-standard-8"], var.machine_type)
    error_message = "This runtime requires x86 E2 standard with at least 2 vCPU/8GB for its 5GB container limit."
  }
}
variable "deletion_protection" {
  type    = bool
  default = true
}
variable "enable_iap_ssh" {
  description = "Network permission only; operator IAP/OS Login IAM must be granted separately."
  type        = bool
  default     = false
}
variable "runtime_env_secret" {
  description = "Existing same-project Secret Manager secret ID containing Docker env-file values; payload never enters Terraform."
  type        = string
  default     = "timing-jeju-be-runtime-env"
  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{1,255}$", var.runtime_env_secret))
    error_message = "Use a Secret Manager ID, not a path or secret value."
  }
}
variable "runtime_env_version" {
  type    = string
  default = "1"
  validation {
    condition     = can(regex("^[1-9][0-9]*$", var.runtime_env_version))
    error_message = "Use an explicit numeric secret version, never latest."
  }
}
variable "secret_files" {
  description = "Mounted at /run/secrets/<filename>; contents are fetched only by the VM."
  type        = map(object({ secret_id = string, version = string }))
  default     = {}
  validation {
    condition     = alltrue([for filename, secret in var.secret_files : can(regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$", filename)) && can(regex("^[a-zA-Z0-9_-]+$", secret.secret_id)) && can(regex("^[1-9][0-9]*$", secret.version))])
    error_message = "Secret filenames must be flat safe basenames, IDs valid, versions numeric."
  }
}
variable "create_secret_containers" {
  description = "Create empty secret resources, never versions; false requires existing secrets."
  type        = bool
  default     = true
}
variable "private_host_mappings" {
  description = "Private MCP TLS hostname to private IPv4; values become Docker add-host entries."
  type        = map(string)
  default     = {}
  validation {
    condition     = alltrue([for host, ip in var.private_host_mappings : can(regex("^[a-zA-Z0-9][a-zA-Z0-9.-]*$", host)) && can(cidrnetmask("${ip}/32")) && (startswith(ip, "10.") || startswith(ip, "192.168.") || can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", ip)))])
    error_message = "Supply safe hostnames and RFC1918 IPv4 addresses."
  }
}
