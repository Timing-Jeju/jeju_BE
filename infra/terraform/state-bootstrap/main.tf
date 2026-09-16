terraform {
  required_version = ">= 1.11, < 2.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.14.0"
    }
  }
  # Deliberately local: this creates the bucket used by the other roots.
  # Keep local state private/backed up, or migrate it to a separate existing bucket.
}

provider "google" { project = var.project_id }
variable "project_id" { type = string }
variable "bucket_name" { type = string }
variable "location" {
  type    = string
  default = "ASIA-NORTHEAST3"
}
variable "terraform_principal" {
  type        = string
  description = "Existing user: or serviceAccount: principal running Terraform. No key is created."
  validation {
    condition     = can(regex("^(user|serviceAccount):[^ ]+@[^ ]+$", var.terraform_principal))
    error_message = "Specify a single existing user or serviceAccount, never allUsers."
  }
}
resource "google_storage_bucket" "state" {
  name                        = var.bucket_name
  location                    = var.location
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  versioning { enabled = true }
  lifecycle { prevent_destroy = true }
}
resource "google_storage_bucket_iam_member" "terraform" {
  bucket = google_storage_bucket.state.name
  role   = "roles/storage.objectAdmin"
  member = var.terraform_principal
}
output "bucket_name" { value = google_storage_bucket.state.name }
