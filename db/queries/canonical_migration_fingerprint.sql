-- Stable application schema and security fingerprint for Issue #215.
-- Extension-owned routines are excluded: pg_get_functiondef rejects aggregate
-- procs (42809), while extension members are infrastructure rather than migration-owned state.
with application_routines as (
  select procedure_record.*
  from pg_catalog.pg_proc procedure_record
  join pg_catalog.pg_namespace namespace on namespace.oid = procedure_record.pronamespace
  where namespace.nspname in ('public', 'timing_jeju_private')
    and procedure_record.prokind in ('f', 'p', 'w')
    and not exists (
      select 1
      from pg_catalog.pg_depend dependency_record
      where dependency_record.classid = 'pg_proc'::regclass
        and dependency_record.objid = procedure_record.oid
        and dependency_record.deptype = 'e'
    )
),
catalog_rows as (
  select 'schema_owner' || ':' || namespace.nspname || ':' || owner_role.rolname as value
  from pg_catalog.pg_namespace namespace
  join pg_catalog.pg_roles owner_role on owner_role.oid = namespace.nspowner
  where namespace.nspname in ('public', 'timing_jeju_private', 'auth')

  union all

  select 'schema_acl' || ':' || namespace.nspname || ':'
      || coalesce(grantee_role.rolname, 'PUBLIC') || ':' || acl_record.privilege_type || ':'
      || acl_record.is_grantable::text || ':' || coalesce(grantor_role.rolname, 'PUBLIC')
  from pg_catalog.pg_namespace namespace
  cross join lateral pg_catalog.aclexplode(
    coalesce(namespace.nspacl, pg_catalog.acldefault('n', namespace.nspowner))
  ) acl_record
  left join pg_catalog.pg_roles grantee_role on grantee_role.oid = acl_record.grantee
  left join pg_catalog.pg_roles grantor_role on grantor_role.oid = acl_record.grantor
  where namespace.nspname in ('public', 'timing_jeju_private', 'auth')

  union all

  select 'relation' || ':' || namespace.nspname || ':' || relation.relname || ':'
      || relation.relkind::text || ':' || relation.relrowsecurity::text || ':'
      || relation.relforcerowsecurity::text
  from pg_catalog.pg_class relation
  join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
  where namespace.nspname in ('public', 'timing_jeju_private')
    and relation.relkind in ('r', 'p', 'v', 'm', 'S', 'f')

  union all

  select 'relation_acl' || ':' || namespace.nspname || ':' || relation.relname || ':'
      || coalesce(grantee_role.rolname, 'PUBLIC') || ':' || acl_record.privilege_type || ':'
      || acl_record.is_grantable::text || ':' || coalesce(grantor_role.rolname, 'PUBLIC')
  from pg_catalog.pg_class relation
  join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
  cross join lateral pg_catalog.aclexplode(
    coalesce(
      relation.relacl,
      pg_catalog.acldefault(
        (case when relation.relkind = 'S' then 'S' else 'r' end)::"char",
        relation.relowner
      )
    )
  ) acl_record
  left join pg_catalog.pg_roles grantee_role on grantee_role.oid = acl_record.grantee
  left join pg_catalog.pg_roles grantor_role on grantor_role.oid = acl_record.grantor
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

  select 'column_acl' || ':' || namespace.nspname || ':' || relation.relname || ':'
      || attribute.attname || ':' || coalesce(grantee_role.rolname, 'PUBLIC') || ':'
      || acl_record.privilege_type || ':' || acl_record.is_grantable::text || ':'
      || coalesce(grantor_role.rolname, 'PUBLIC')
  from pg_catalog.pg_attribute attribute
  join pg_catalog.pg_class relation on relation.oid = attribute.attrelid
  join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
  cross join lateral pg_catalog.aclexplode(attribute.attacl) acl_record
  left join pg_catalog.pg_roles grantee_role on grantee_role.oid = acl_record.grantee
  left join pg_catalog.pg_roles grantor_role on grantor_role.oid = acl_record.grantor
  where namespace.nspname in ('public', 'timing_jeju_private')
    and attribute.attnum > 0
    and not attribute.attisdropped
    and attribute.attacl is not null

  union all

  select 'constraint' || ':' || namespace.nspname || ':' || constraint_record.conname || ':'
      || pg_catalog.pg_get_constraintdef(constraint_record.oid, true)
  from pg_catalog.pg_constraint constraint_record
  join pg_catalog.pg_namespace namespace on namespace.oid = constraint_record.connamespace
  where namespace.nspname in ('public', 'timing_jeju_private')

  union all

  select 'index' || ':' || index_record.schemaname || ':' || index_record.tablename || ':'
      || index_record.indexname || ':' || index_record.indexdef
  from pg_catalog.pg_indexes index_record
  where index_record.schemaname in ('public', 'timing_jeju_private')

  union all

  select 'trigger' || ':' || namespace.nspname || ':' || relation.relname || ':'
      || trigger_record.tgname || ':' || pg_catalog.pg_get_triggerdef(trigger_record.oid, true)
  from pg_catalog.pg_trigger trigger_record
  join pg_catalog.pg_class relation on relation.oid = trigger_record.tgrelid
  join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
  where namespace.nspname in ('public', 'timing_jeju_private')
    and not trigger_record.tgisinternal

  union all

  select 'policy' || ':' || policy_record.schemaname || ':' || policy_record.tablename || ':'
      || policy_record.policyname || ':' || policy_record.permissive || ':' || policy_record.cmd || ':'
      || coalesce((
        select string_agg(policy_role.role_name, ',' order by policy_role.role_name)
        from unnest(policy_record.roles) as policy_role(role_name)
      ), '') || ':' || coalesce(policy_record.qual, '') || ':'
      || coalesce(policy_record.with_check, '')
  from pg_catalog.pg_policies policy_record
  where policy_record.schemaname in ('public', 'timing_jeju_private')

  union all

  select 'grant' || ':' || grant_record.grantee || ':' || grant_record.table_schema || ':'
      || grant_record.table_name || ':' || grant_record.privilege_type || ':'
      || grant_record.is_grantable
  from information_schema.role_table_grants grant_record
  where grant_record.table_schema = 'public'
    and grant_record.grantee in ('PUBLIC', 'anon', 'authenticated', 'service_role')

  union all

  select 'function' || ':' || procedure_record.oid::regprocedure::text || ':'
      || pg_catalog.pg_get_functiondef(procedure_record.oid)
  from application_routines procedure_record

  union all

  select 'function_acl' || ':' || procedure_record.oid::regprocedure::text || ':'
      || coalesce(grantee_role.rolname, 'PUBLIC') || ':' || acl_record.privilege_type || ':'
      || acl_record.is_grantable::text || ':' || coalesce(grantor_role.rolname, 'PUBLIC')
  from application_routines procedure_record
  cross join lateral pg_catalog.aclexplode(
    coalesce(procedure_record.proacl, pg_catalog.acldefault('f', procedure_record.proowner))
  ) acl_record
  left join pg_catalog.pg_roles grantee_role on grantee_role.oid = acl_record.grantee
  left join pg_catalog.pg_roles grantor_role on grantor_role.oid = acl_record.grantor
)
select md5(string_agg(value, E'\n' order by value)) as schema_acl_fingerprint
from catalog_rows;
