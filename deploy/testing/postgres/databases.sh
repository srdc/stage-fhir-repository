#!/bin/sh
# Databases of the shared PostgreSQL (container postgres): one database and one login role per service, owned by that
# role, so that the services do not use the superuser and cannot reach each other's data. Idempotent: runs on every
# `docker compose up` (service postgres-setup), for a new volume as well as for an existing one (objects created earlier
# by the superuser are handed over to the service's role).
#
# Passwords: /run/secrets/postgres (superuser, local/postgres.secret) and /run/secrets/<database>
# (local/postgres-<database>.secret), written by setup.sh. To add a service: a secret file, a mount and a line at the end.
set -eu

# PGHOST: the PostgreSQL container (deploy/testing: postgres; deploy/kroniq: stage-db)
export PGHOST=${PGHOST:-postgres} PGUSER=postgres
PGPASSWORD="$(cat /run/secrets/postgres)"
export PGPASSWORD

until pg_isready -q; do sleep 1; done

# database <name>: database and role <name>, password from /run/secrets/<name>
database() {
  name=$1
  password=$(cat "/run/secrets/$name")
  psql -v ON_ERROR_STOP=1 -q -d postgres -v name="$name" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'name') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'name') \gexec
ALTER ROLE :"name" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'password';
SELECT format('CREATE DATABASE %I OWNER %I', :'name', :'name') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'name') \gexec
ALTER DATABASE :"name" OWNER TO :"name";
REVOKE ALL ON DATABASE :"name" FROM PUBLIC;
SQL
  # tables, sequences and views created before by another role (e.g. the superuser) belong to the service
  # (a table's owned sequences move with it)
  psql -v ON_ERROR_STOP=1 -q -d "$name" -v name="$name" <<'SQL'
ALTER SCHEMA public OWNER TO :"name";
SELECT format('ALTER TABLE %I.%I OWNER TO %I', schemaname, tablename, :'name') FROM pg_tables
  WHERE schemaname = 'public' AND tableowner <> :'name' \gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO %I', schemaname, sequencename, :'name') FROM pg_sequences
  WHERE schemaname = 'public' AND sequenceowner <> :'name' \gexec
SELECT format('ALTER VIEW %I.%I OWNER TO %I', schemaname, viewname, :'name') FROM pg_views
  WHERE schemaname = 'public' AND viewowner <> :'name' \gexec
SQL
  echo "database $name: ready"
}

# onfhir-feast: jobs, datasets and its akka persistence
database onfhir_feast
