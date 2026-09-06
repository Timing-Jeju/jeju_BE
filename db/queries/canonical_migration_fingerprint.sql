-- Stable public schema, RLS, grant, trigger, function-ACL fingerprint for Issue #215.
with catalog_rows as (
  select 'relation' || ':' || namespace.nspname || ':' || relation.relname || ':'
      || relation.relkind || ':' || relation.relrowsecurity || ':' || relation.relforcerowsecurity
      as value
  from pg_catalog.pg_class relation
  join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
  where namespace.nspname in ('public', 'timing_jeju_private')
    and relation.relkind in ('r', 'p', 'v', 'm', 'S', 'f')

  union all

  select 'column' || ':' || column_record.table_schema || ':' || column_record.table_name || ':'
      || column_record.ordinal_position || ':' || column_record.column_name || ':'
      || column_record.udt_schema || ':' || column_record.udt_name || ':'
      || column_record.is_nullable || ':' || coalesce(column_record.column_default, '')
  from information_schema.columns column_record
  where column_record.table_schema in ('public', 'timing_jeju_private')

  union all

  select 'constraint' || ':' || constraint_record.conname || ':'
      || pg_catalog.pg_get_constraintdef(constraint_record.oid, true) as value
  from pg_catalog.pg_constraint constraint_record
  join pg_catalog.pg_namespace namespace on namespace.oid = constraint_record.connamespace
  where namespace.nspname in ('public', 'timing_jeju_private')

  union all

  select 'index' || ':' || index_record.schemaname || ':' || index_record.tablename || ':'
      || index_record.indexname || ':' || index_record.indexdef
  from pg_catalog.pg_indexes index_record
  where index_record.schemaname in ('public', 'timing_jeju_private')

  union all

  select 'trigger' || ':' || trigger_record.tgname || ':'
      || pg_catalog.pg_get_triggerdef(trigger_record.oid, true)
  from pg_catalog.pg_trigger trigger_record
  join pg_catalog.pg_class relation on relation.oid = trigger_record.tgrelid
  join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
  where namespace.nspname = 'public'
    and not trigger_record.tgisinternal

  union all

  select 'policy' || ':' || policy_record.schemaname || ':' || policy_record.tablename || ':'
      || policy_record.policyname || ':' || policy_record.cmd || ':'
      || coalesce(policy_record.qual, '') || ':' || coalesce(policy_record.with_check, '')
  from pg_catalog.pg_policies policy_record
  where policy_record.schemaname = 'public'

  union all

  select 'grant' || ':' || grant_record.grantee || ':' || grant_record.table_schema || ':'
      || grant_record.table_name || ':' || grant_record.privilege_type
  from information_schema.role_table_grants grant_record
  where grant_record.table_schema = 'public'
    and grant_record.grantee in ('anon', 'authenticated', 'service_role')

  union all

  select 'function' || ':' || procedure_record.oid::regprocedure::text || ':'
      || pg_catalog.pg_get_functiondef(procedure_record.oid)
  from pg_catalog.pg_proc procedure_record
  join pg_catalog.pg_namespace namespace on namespace.oid = procedure_record.pronamespace
  where namespace.nspname in ('public', 'timing_jeju_private')

  union all

  select 'function_acl' || ':' || procedure_record.oid::regprocedure::text || ':'
      || coalesce(role_record.rolname, 'PUBLIC') || ':' || acl_record.privilege_type
  from pg_catalog.pg_proc procedure_record
  join pg_catalog.pg_namespace namespace on namespace.oid = procedure_record.pronamespace
  cross join lateral pg_catalog.aclexplode(
    coalesce(procedure_record.proacl, pg_catalog.acldefault('f', procedure_record.proowner))
  ) acl_record
  left join pg_catalog.pg_roles role_record on role_record.oid = acl_record.grantee
  where namespace.nspname in ('public', 'timing_jeju_private')
)
select md5(string_agg(value, E'\n' order by value)) as schema_acl_fingerprint
from catalog_rows;
