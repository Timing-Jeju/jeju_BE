-- Issue #23: 외부 API 원문 snapshot의 redaction·크기·보존 감사 계약.

alter table public.external_api_snapshots
  alter column raw_payload drop not null,
  add column payload_size_bytes bigint,
  add column redaction_version text not null default 'legacy-unversioned',
  add column purged_at timestamptz;

update public.external_api_snapshots
set payload_size_bytes = octet_length(convert_to(raw_payload::text, 'UTF8'));

alter table public.external_api_snapshots
  alter column payload_size_bytes set default 0,
  alter column payload_size_bytes set not null,
  add constraint ck_external_snapshots_payload_size
    check (payload_size_bytes between 0 and 2097152),
  add constraint ck_external_snapshots_redaction_version
    check (btrim(redaction_version) <> '' and octet_length(redaction_version) <= 128),
  add constraint ck_external_snapshots_purge_state
    check (
      purged_at is null
      or (raw_payload is null and purge_after is not null and purged_at >= purge_after)
    );

create or replace function public.protect_external_snapshot_identity()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.import_run_id is distinct from new.import_run_id
     or old.source_provider is distinct from new.source_provider
     or old.source_service is distinct from new.source_service
     or old.source_operation is distinct from new.source_operation
     or old.scope_key is distinct from new.scope_key then
    raise exception using errcode = '23514', message = 'external snapshot source identity is immutable';
  end if;

  if old.external_record_id is distinct from new.external_record_id
     or old.request_hash is distinct from new.request_hash
     or old.page_key is distinct from new.page_key
     or old.http_status is distinct from new.http_status
     or old.provider_result_code is distinct from new.provider_result_code
     or old.fetched_at is distinct from new.fetched_at
     or old.source_modified_at is distinct from new.source_modified_at
     or old.expires_at is distinct from new.expires_at
     or old.parser_version is distinct from new.parser_version
     or old.payload_hash is distinct from new.payload_hash
     or old.request_metadata_redacted is distinct from new.request_metadata_redacted
     or old.payload_size_bytes is distinct from new.payload_size_bytes
     or old.redaction_version is distinct from new.redaction_version then
    raise exception using errcode = '23514', message = 'external snapshot audit payload is immutable';
  end if;

  if old.parse_status <> 'received' and old.parse_status is distinct from new.parse_status then
    raise exception using errcode = '23514', message = 'external snapshot terminal status is immutable';
  end if;

  if (old.parse_status <> 'received'
      or new.parse_status = 'received')
     and (old.parsed_at is distinct from new.parsed_at
          or old.error_code is distinct from new.error_code
          or old.error_message is distinct from new.error_message
          or old.purge_after is distinct from new.purge_after) then
    raise exception using errcode = '23514', message = 'external snapshot state audit is immutable';
  end if;

  if old.raw_payload is distinct from new.raw_payload then
    if not (old.raw_payload is not null and new.raw_payload is null and new.purged_at is not null) then
      raise exception using errcode = '23514', message = 'external snapshot audit payload is immutable';
    end if;
  end if;

  if old.purged_at is not null and new.purged_at is distinct from old.purged_at then
    raise exception using errcode = '23514', message = 'external snapshot purge timestamp is immutable';
  end if;
  return new;
end;
$$;

drop trigger trg_external_snapshots_immutable_identity on public.external_api_snapshots;

create trigger trg_external_snapshots_immutable_identity
before update of
  import_run_id, source_provider, source_service, source_operation, scope_key,
  external_record_id, request_hash, page_key, http_status, provider_result_code,
  fetched_at, source_modified_at, expires_at, parser_version, payload_hash,
  request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
  purged_at, parse_status, parsed_at, error_code, error_message, purge_after
on public.external_api_snapshots
for each row execute function public.protect_external_snapshot_identity();

revoke all on public.external_api_snapshots from anon, authenticated;
