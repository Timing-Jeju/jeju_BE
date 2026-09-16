mock_provider "google" {}
variables {
  project_id          = "test-project"
  bucket_name         = "synthetic-test-state"
  terraform_principal = "serviceAccount:terraform@test-project.iam.gserviceaccount.com"
}
run "private_versioned_state" {
  command = plan
  assert {
    condition = (
      google_storage_bucket.state.public_access_prevention == "enforced" &&
      google_storage_bucket.state.uniform_bucket_level_access &&
      google_storage_bucket.state.versioning[0].enabled &&
      !google_storage_bucket.state.force_destroy
    )
    error_message = "state bucket은 비공개/버전관리/강제삭제금지여야 합니다."
  }
}
