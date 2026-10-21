#!/usr/bin/env bash
# Container-side migration runner. SQL remains intact, including DELIMITER blocks.
set -Eeuo pipefail
umask 077
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
MODE=${1:-}
shift || true
RETRY=false
BACKUP_DIR=
case "$MODE" in
  initialize|inspect) [[ $# == 0 ]] || { echo "Unexpected arguments" >&2; exit 2; } ;;
  upgrade) if [[ ${1:-} == --retry ]]; then RETRY=true; shift; fi
    [[ $# == 0 ]] || { echo "Unexpected arguments" >&2; exit 2; } ;;
  backup) BACKUP_DIR=${1:-}
    [[ $# == 2 && ${2:-} == --writers-stopped && $BACKUP_DIR =~ ^/tmp/silvercare-backup-[a-f0-9]+$ ]] ||
      { echo "backup requires /tmp/silvercare-backup-<hex> --writers-stopped" >&2; exit 2; } ;;
  *) echo "Usage: bash migrate.sh initialize|upgrade [--retry]|inspect|backup /tmp/silvercare-backup-<hex> --writers-stopped" >&2; exit 2 ;;
esac
export MYSQL_PWD=${MYSQL_PWD:-${MYSQL_ROOT_PASSWORD:?MYSQL_PWD or MYSQL_ROOT_PASSWORD is required}}
MYSQL=(mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names --unbuffered --skip-reconnect)
WORK=$(mktemp -d)
RUN_ID=$(cat /proc/sys/kernel/random/uuid)
CURRENT=
MYSQL_PID=
WRITER_PID=
INPUT=
OUTPUT=
REPLY=
SEQUENCE=0
fail() { echo "Migration error: $*" >&2; exit 1; }
cleanup() {
  local code=$?
  trap - EXIT ERR TERM INT PIPE
  if [[ -n $WRITER_PID ]]; then
    kill "$WRITER_PID" 2>/dev/null || true
    wait "$WRITER_PID" 2>/dev/null || true
  fi
  if [[ -n $INPUT ]]; then eval "exec ${INPUT}>&-" 2>/dev/null || true; fi
  if [[ -n $OUTPUT ]]; then eval "exec ${OUTPUT}<&-" 2>/dev/null || true; fi
  if [[ -n $MYSQL_PID ]]; then wait "$MYSQL_PID" 2>/dev/null || true; fi
  # On a SQL error the batch client exits and releases its session lock. A fresh
  # session may record failure only if it reacquires that same lock and run ID.
  if [[ $code != 0 && -n $CURRENT ]]; then
    "${MYSQL[@]}" 2>/dev/null <<SQL || true
SET @owned = GET_LOCK('db_beadhouse.schema_migration',0);
UPDATE db_beadhouse.schema_migration
SET status='FAILED',finished_at=UTC_TIMESTAMP(6)
WHERE @owned=1 AND version='$CURRENT' AND run_id='$RUN_ID' AND status='RUNNING';
DO RELEASE_LOCK('db_beadhouse.schema_migration');
SQL
  fi
  rm -rf -- "$WORK"
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 1' PIPE

declare -a VERSIONS=() FILES=() HASHES=() RETRYABLE=()
MANIFEST_VALID=true
manifest_issue() {
  if [[ $MODE == backup ]]; then MANIFEST_VALID=false
  else fail "$*"; fi
}
expected=1
if [[ -f $ROOT/migrations.tsv ]]; then
while IFS=$'\t' read -r version file checksum retryable extra || [[ -n ${version:-} ]]; do
  version=${version%$'\r'}; retryable=${retryable%$'\r'}
  [[ -z $version || $version == \#* ]] && continue
  printf -v wanted '%04d' "$expected"
  [[ $version == "$wanted" && $file =~ ^[a-z0-9_]+\.sql$ && $checksum =~ ^[a-f0-9]{64}$ &&
     $retryable =~ ^(true|false)$ && -z $extra ]] || { manifest_issue "Invalid migration manifest at version $version"; continue; }
  [[ -f $ROOT/$file && ! -L $ROOT/$file ]] || { manifest_issue "Missing migration file $file"; continue; }
  sed 's/\r$//' "$ROOT/$file" > "$WORK/$file"
  actual=$(sha256sum "$WORK/$file"); actual=${actual%% *}
  [[ $actual == "$checksum" ]] || manifest_issue "Migration checksum mismatch: $version $file"
  VERSIONS+=("$version"); FILES+=("$file"); HASHES+=("$checksum"); RETRYABLE+=("$retryable")
  expected=$((expected + 1))
done < "$ROOT/migrations.tsv"
else manifest_issue "Missing migration manifest"; fi
[[ ${#VERSIONS[@]} -gt 0 ]] || manifest_issue "Empty migration manifest"

coproc DB { exec "${MYSQL[@]}"; }
MYSQL_PID=$DB_PID
# Duplicate descriptors because Bash may unset the coproc array after early exit.
exec {INPUT}>&"${DB[1]}"
exec {OUTPUT}<&"${DB[0]}"
exec {DB[1]}>&-
exec {DB[0]}<&-
exchange() {
  local sql=$1 line marker
  SEQUENCE=$((SEQUENCE + 1))
  marker="__silvercare_${RUN_ID//-/}_${SEQUENCE}__"
  REPLY=
  printf '%s\nSELECT '\''%s'\'';\n' "$sql" "$marker" >&"$INPUT" || fail "Database session closed"
  while IFS= read -r line <&"$OUTPUT"; do
    [[ $line == "$marker" ]] && return 0
    REPLY+="${REPLY:+$'\n'}$line"
  done
  fail "Database statement failed; no success marker received"
}
execute_file() {
  local file=$1 line marker
  SEQUENCE=$((SEQUENCE + 1)); marker="__silvercare_${RUN_ID//-/}_${SEQUENCE}__"
  # Drain results while feeding SQL. A migration can emit more than a pipe's
  # buffer before mysql consumes its remaining input.
  (
    trap - EXIT ERR TERM INT PIPE
    cat -- "$file" >&"$INPUT" &&
      printf '\nDELIMITER ;\nSELECT '\''%s'\'';\n' "$marker" >&"$INPUT"
  ) &
  WRITER_PID=$!
  while IFS= read -r line <&"$OUTPUT"; do
    if [[ $line == "$marker" ]]; then
      wait "$WRITER_PID" || fail "Database SQL writer failed"
      WRITER_PID=
      return 0
    fi
    # SQL may print diagnostics, but never echo the complete query or credentials.
    printf '%s\n' "$line" >&2
  done
  fail "Migration SQL failed; no success marker received"
}
exchange "SELECT GET_LOCK('db_beadhouse.schema_migration',0);"
[[ $REPLY == 1 ]] || fail "Another migration or backup holds the database lock"
exchange "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='db_beadhouse';"
DB_EXISTS=$REPLY
exchange "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_beadhouse' AND table_name='schema_migration';"
HAS_REGISTRY=$REPLY

registry_issue() {
  if [[ $MODE == backup ]]; then
    READINESS=NOT_READY
    echo "Backup source is not migration-ready: $*" >&2
  else fail "$*"; fi
}
validate_registry() {
  local row v f h state run attempts started finished index previous=0 incomplete=false
  [[ $HAS_REGISTRY == 1 ]] || { READINESS=UNVERSIONED; return 0; }
  exchange "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_beadhouse' AND table_name='schema_metadata';"
  [[ $REPLY == 1 ]] || { registry_issue "Migration registry exists without database origin metadata"; return; }
  exchange "SELECT COUNT(*) FROM db_beadhouse.schema_metadata WHERE id=1 AND origin IN ('DEMO_SEEDED','LEGACY_ADOPTED');"
  [[ $REPLY == 1 ]] || { registry_issue "Database origin metadata is missing or invalid"; return; }
  exchange "SELECT version,filename,checksum,status,run_id,attempts,started_at,COALESCE(finished_at,'') FROM db_beadhouse.schema_migration ORDER BY version;"
  REGISTRY=$REPLY
  STATES=()
  while IFS=$'\t' read -r v f h state run attempts started finished; do
    [[ -z $v ]] && continue
    [[ $v =~ ^[0-9]{4}$ ]] || { registry_issue "Unknown migration version"; return; }
    index=$((10#$v - 1))
    [[ $index -ge 0 && $index -lt ${#VERSIONS[@]} && $v == "${VERSIONS[$index]}" ]] || { registry_issue "Unknown migration version $v"; return; }
    [[ $index == "$previous" ]] || { registry_issue "Migration version gap before $v"; return; }
    [[ $f == "${FILES[$index]}" && $h == "${HASHES[$index]}" ]] || { registry_issue "Recorded migration checksum/filename drift: $v"; return; }
    [[ $state =~ ^(RUNNING|SUCCEEDED|FAILED|INTERRUPTED)$ ]] || { registry_issue "Unknown migration state: $v"; return; }
    [[ $incomplete == false ]] || { registry_issue "Migration exists after an unfinished version: $v"; return; }
    STATES[$index]=$state
    [[ $state == SUCCEEDED ]] || incomplete=true
    previous=$((previous + 1))
  done <<< "$REGISTRY"
  READINESS=NOT_READY
  if [[ $previous == ${#VERSIONS[@]} && $incomplete == false ]]; then READINESS=READY; fi
}
declare -a STATES=()
READINESS=UNVERSIONED
REGISTRY=
if [[ $MODE == backup && $MANIFEST_VALID == false ]]; then
  if [[ $HAS_REGISTRY == 1 ]]; then READINESS=NOT_READY; fi
else validate_registry; fi

registry_json() {
  if [[ $HAS_REGISTRY == 1 ]]; then
    exchange "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('version',version,'filename',filename,'checksum',checksum,'status',status,'run_id',run_id,'attempts',attempts,'started_at',started_at,'finished_at',finished_at)),JSON_ARRAY()) FROM db_beadhouse.schema_migration;"
  else REPLY='[]'; fi
}

schema_signature() {
  local table signature_material=
  exchange "SELECT table_name,table_type FROM information_schema.tables WHERE table_schema='db_beadhouse' ORDER BY table_name;"
  signature_material=$REPLY
  while IFS=$'\t' read -r table _; do
    [[ -z $table ]] && continue
    [[ $table =~ ^[A-Za-z0-9_]+$ ]] || fail "Unsupported table identifier in backup"
    exchange "SHOW CREATE TABLE db_beadhouse.\`$table\`;"
    signature_material+=$'\n'"$REPLY"
  done <<< "$signature_material"
  exchange "SELECT routine_type,routine_name,COALESCE(routine_definition,''),sql_mode,security_type,definer,character_set_client,collation_connection,database_collation FROM information_schema.routines WHERE routine_schema='db_beadhouse' ORDER BY routine_type,routine_name;"
  signature_material+=$'\n'"$REPLY"
  exchange "SELECT trigger_name,event_manipulation,event_object_table,action_order,action_statement,action_orientation,action_timing,sql_mode,definer,character_set_client,collation_connection,database_collation FROM information_schema.triggers WHERE trigger_schema='db_beadhouse' ORDER BY trigger_name;"
  signature_material+=$'\n'"$REPLY"
  exchange "SELECT event_name,event_definition,event_type,execute_at,interval_value,interval_field,starts,ends,status,on_completion,sql_mode,definer,time_zone,character_set_client,collation_connection,database_collation FROM information_schema.events WHERE event_schema='db_beadhouse' ORDER BY event_name;"
  signature_material+=$'\n'"$REPLY"
  SCHEMA_SIGNATURE=$(printf '%s' "$signature_material" | sha256sum)
}

if [[ $MODE == inspect ]]; then
  registry_json
  printf '{"database":"db_beadhouse","readiness":"%s","migrations":%s}\n' "$READINESS" "$REPLY"
  [[ $READINESS == READY ]] || fail "Database migrations are not complete"
  exit 0
fi

if [[ $MODE == backup ]]; then
  [[ $DB_EXISTS == 1 ]] || fail "Database does not exist"
  [[ ! -e $BACKUP_DIR ]] || fail "Backup directory already exists"
  mkdir -m 700 -- "$BACKUP_DIR"
  schema_signature
  INITIAL_SCHEMA_SIGNATURE=$SCHEMA_SIGNATURE
  exchange "SELECT VERSION();"; SERVER_VERSION=$REPLY
  registry_json; MIGRATIONS_JSON=$REPLY
  exchange "SELECT table_name FROM information_schema.tables WHERE table_schema='db_beadhouse' AND table_type='BASE TABLE' ORDER BY table_name;"
  TABLE_NAMES=$REPLY
  TABLES_JSON='['; separator=
  while IFS= read -r table; do
    [[ -z $table ]] && continue
    # Restrict identifiers before interpolating into SQL. Existing schema uses
    # ASCII identifiers; unusual user-created names require deliberate handling.
    [[ $table =~ ^[A-Za-z0-9_]+$ ]] || fail "Unsupported table identifier in backup"
    exchange "SELECT COUNT(*) FROM db_beadhouse.\`$table\`;"; count=$REPLY
    exchange "CHECKSUM TABLE db_beadhouse.\`$table\` EXTENDED;"; digest=${REPLY##*$'\t'}
    [[ $count =~ ^[0-9]+$ && $digest =~ ^[0-9]+$ ]] || fail "Cannot checksum table $table"
    TABLES_JSON+="$separator{\"name\":\"$table\",\"rows\":$count,\"checksum\":$digest}"; separator=,
  done <<< "$TABLE_NAMES"
  TABLES_JSON+=']'
  exchange "SELECT JSON_OBJECT('routines',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('name',routine_name,'type',routine_type)) FROM information_schema.routines WHERE routine_schema='db_beadhouse'),JSON_ARRAY()),'events',COALESCE((SELECT JSON_ARRAYAGG(event_name) FROM information_schema.events WHERE event_schema='db_beadhouse'),JSON_ARRAY()),'triggers',COALESCE((SELECT JSON_ARRAYAGG(trigger_name) FROM information_schema.triggers WHERE trigger_schema='db_beadhouse'),JSON_ARRAY()));"
  OBJECTS_JSON=$REPLY
  exchange "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('table',tc.table_name,'name',tc.constraint_name,'type',tc.constraint_type,'columns',(SELECT GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position SEPARATOR ',') FROM information_schema.key_column_usage k WHERE k.constraint_schema=tc.constraint_schema AND k.table_name=tc.table_name AND k.constraint_name=tc.constraint_name),'expression',cc.check_clause)),JSON_ARRAY()) FROM information_schema.table_constraints tc LEFT JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name AND tc.constraint_type='CHECK' WHERE tc.constraint_schema='db_beadhouse';"
  CONSTRAINTS_JSON=$REPLY
  exchange "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_beadhouse' AND table_name='schema_metadata';"
  ORIGIN_JSON=null
  if [[ $REPLY == 1 ]]; then
    exchange "SELECT JSON_QUOTE(origin) FROM db_beadhouse.schema_metadata WHERE id=1;"
    ORIGIN_JSON=${REPLY:-null}
  fi
  mysqldump --user=root --default-character-set=utf8mb4 --single-transaction --quick \
    --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF \
    --databases db_beadhouse --result-file="$BACKUP_DIR/database.sql" ||
      fail "mysqldump failed; no backup manifest published"
  # The acknowledgment is required because application writers do not participate
  # in this named lock. Verify table summaries again to catch accidental writes.
  while IFS= read -r table; do
    [[ -z $table ]] && continue
    exchange "SELECT COUNT(*) FROM db_beadhouse.\`$table\`;"; count=$REPLY
    exchange "CHECKSUM TABLE db_beadhouse.\`$table\` EXTENDED;"; digest=${REPLY##*$'\t'}
    [[ $TABLES_JSON == *"{\"name\":\"$table\",\"rows\":$count,\"checksum\":$digest}"* ]] ||
      fail "Database changed during backup: $table"
  done <<< "$TABLE_NAMES"
  schema_signature
  [[ $SCHEMA_SIGNATURE == "$INITIAL_SCHEMA_SIGNATURE" ]] ||
    fail "Database schema or stored objects changed during backup"
  digest=$(sha256sum "$BACKUP_DIR/database.sql"); digest=${digest%% *}
  bytes=$(wc -c < "$BACKUP_DIR/database.sql")
  printf '{"format":1,"database":"db_beadhouse","file":"database.sql","sha256":"%s","bytes":%s,"mysql_version":"%s","created_at":"%s","source_readiness":"%s","origin":%s,"migrations":%s,"tables":%s,"objects":%s,"constraints":%s}\n' \
    "$digest" "$bytes" "$SERVER_VERSION" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$READINESS" "$ORIGIN_JSON" "$MIGRATIONS_JSON" "$TABLES_JSON" "$OBJECTS_JSON" "$CONSTRAINTS_JSON" > "$BACKUP_DIR/manifest.json.tmp"
  mv -- "$BACKUP_DIR/manifest.json.tmp" "$BACKUP_DIR/manifest.json"
  echo "Backup complete: $BACKUP_DIR"
  exit 0
fi

if [[ $MODE == initialize ]]; then
  exchange "SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_beadhouse') + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='db_beadhouse') + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema='db_beadhouse');"
  [[ $REPLY == 0 ]] || fail "initialize requires an empty database; use upgrade for an existing database"
  exchange "CREATE DATABASE IF NOT EXISTS db_beadhouse CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; USE db_beadhouse;"
  sed 's/\r$//' "$ROOT/db_beadhouse.sql" > "$WORK/seed.sql"
  execute_file "$WORK/seed.sql"
  ORIGIN=DEMO_SEEDED
else
  [[ $DB_EXISTS == 1 ]] || fail "Legacy database does not exist; upgrade never imports demo data"
  exchange "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_beadhouse' AND table_name IN ('elder','staff','bed','reserve','retreat_apply');"
  [[ $REPLY == 5 ]] || fail "Expected legacy business tables are missing; upgrade never imports demo data"
  ORIGIN=LEGACY_ADOPTED
fi
exchange "USE db_beadhouse;
CREATE TABLE IF NOT EXISTS schema_metadata (
  id TINYINT NOT NULL PRIMARY KEY, origin VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL,
  CONSTRAINT chk_schema_metadata_singleton CHECK (id=1),
  CONSTRAINT chk_schema_metadata_origin CHECK (origin IN ('DEMO_SEEDED','LEGACY_ADOPTED'))
) ENGINE=InnoDB;
INSERT IGNORE INTO schema_metadata(id,origin,created_at) VALUES(1,'$ORIGIN',UTC_TIMESTAMP(6));
CREATE TABLE IF NOT EXISTS schema_migration (
  version CHAR(4) NOT NULL PRIMARY KEY, filename VARCHAR(128) NOT NULL,
  checksum CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, run_id CHAR(36) NOT NULL,
  attempts INT UNSIGNED NOT NULL, started_at DATETIME(6) NOT NULL, finished_at DATETIME(6) NULL,
  CONSTRAINT chk_schema_migration_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED'))
) ENGINE=InnoDB;"
HAS_REGISTRY=1
validate_registry
for index in "${!VERSIONS[@]}"; do
  version=${VERSIONS[$index]}; state=${STATES[$index]:-}
  [[ $state == SUCCEEDED ]] && continue
  if [[ $state == RUNNING ]]; then
    exchange "UPDATE schema_migration SET status='INTERRUPTED',finished_at=UTC_TIMESTAMP(6) WHERE version='$version' AND status='RUNNING';"
    fail "Interrupted migration $version recorded; inspect it before upgrade --retry"
  fi
  if [[ -n $state ]]; then
    [[ $RETRY == true && ${RETRYABLE[$index]} == true ]] ||
      fail "Migration $version is $state; requires a retryable migration and explicit upgrade --retry"
  fi
  exchange "INSERT INTO schema_migration(version,filename,checksum,status,run_id,attempts,started_at,finished_at)
VALUES('$version','${FILES[$index]}','${HASHES[$index]}','RUNNING','$RUN_ID',1,UTC_TIMESTAMP(6),NULL)
ON DUPLICATE KEY UPDATE status='RUNNING',run_id='$RUN_ID',attempts=attempts+1,started_at=UTC_TIMESTAMP(6),finished_at=NULL;"
  CURRENT=$version
  echo "Applying $version ${FILES[$index]}" >&2
  execute_file "$WORK/${FILES[$index]}"
  exchange "UPDATE schema_migration SET status='SUCCEEDED',finished_at=UTC_TIMESTAMP(6) WHERE version='$version' AND run_id='$RUN_ID' AND status='RUNNING';"
  CURRENT=
done
validate_registry
[[ $READINESS == READY ]] || fail "Migration sequence remains incomplete"
echo "Database ready: ${#VERSIONS[@]} migrations verified"
