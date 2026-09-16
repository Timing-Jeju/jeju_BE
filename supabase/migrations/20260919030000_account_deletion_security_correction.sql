-- Issue #61 reviewer correction: lifetime-independent deny identity and secret cleanup access path.

alter table public.account_deletion_requests
  add column auth_subject_fingerprint bytea;

alter table public.account_deletion_requests
  add constraint account_deletion_auth_subject_fingerprint_size
  check (auth_subject_fingerprint is not null
    and octet_length(auth_subject_fingerprint) = 32) not valid;

create index account_deletion_subject_deny_idx
  on public.account_deletion_requests (auth_subject_fingerprint, status)
  where status <> 'cancelled';

create index account_deletion_secret_cleanup_idx
  on public.account_deletion_requests (status_token_expires_at, id)
  where status_token_ciphertext is not null;

-- Existing table ACL and forced RLS remain unchanged. This migration grants no new authority.
