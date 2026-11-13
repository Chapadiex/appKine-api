#!/usr/bin/env bash
# Compara tiempos de consultas representativas en MySQL y en PostgreSQL con los MISMOS datos.
#
# Herramienta de la migracion (12/11/2026). Corre cada consulta con EXPLAIN ANALYZE N veces en
# cada motor y reporta la mediana del tiempo de ejecucion en el servidor (sin red ni driver).
# Requiere los contenedores akine-mysql y akine-postgres y una base copiada con TransferirDatos.
#
# Uso: comparar-rendimiento.sh <base-mysql> <base-postgres> [repeticiones]
set -euo pipefail
MY_DB="$1"; PG_DB="$2"; N="${3:-7}"

# Cada medicion corre la consulta dos veces en la misma sesion y toma la segunda: con el pool de
# conexiones la sesion ya esta caliente (en PostgreSQL, la primera comparacion con akine_ci carga el
# collator ICU de esa conexion, ~10 ms una sola vez).
mysql_ms() {  # tiempo real del nodo raiz de EXPLAIN ANALYZE, en ms
	docker exec -i akine-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N '"$MY_DB" \
		<<<"EXPLAIN ANALYZE $1; EXPLAIN ANALYZE $1;" \
		| grep 'actual time' | sed -n 2p | sed -E 's/.*actual time=[0-9.]+\.\.([0-9.]+).*/\1/'
}
pg_ms() {
	docker exec -i akine-postgres psql -U postgres -d "$PG_DB" -At \
		<<<"EXPLAIN (ANALYZE, TIMING OFF) $1; EXPLAIN (ANALYZE, TIMING OFF) $1;" \
		| grep 'Execution Time' | tail -1 | sed -E 's/.*: ([0-9.]+) ms/\1/'
}
mediana() { sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'; }

medir() {  # nombre, sql mysql, sql postgres
	local my pg
	my=$(for _ in $(seq "$N"); do mysql_ms "$2"; done | mediana)
	pg=$(for _ in $(seq "$N"); do pg_ms "$3"; done | mediana)
	printf '| %s | %s | %s |\n' "$1" "$my" "$pg"
}

echo "| Consulta | MySQL 8.4 (ms) | PostgreSQL 18 (ms) |"
echo "|---|---:|---:|"
ORG=$(docker exec akine-postgres psql -U postgres -d "$PG_DB" -At -c "SELECT organization_id FROM turno GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 1")
SEDE=$(docker exec akine-postgres psql -U postgres -d "$PG_DB" -At -c "SELECT consultorio_id FROM turno WHERE organization_id = $ORG GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 1")

medir "Turnos de la sede en un mes (agenda)" \
	"SELECT * FROM turno WHERE organization_id = $ORG AND consultorio_id = $SEDE AND inicio >= '2026-11-01' AND inicio < '2026-12-01' AND deleted_at IS NULL ORDER BY inicio" \
	"SELECT * FROM turno WHERE organization_id = $ORG AND consultorio_id = $SEDE AND inicio >= '2026-11-01Z' AND inicio < '2026-12-01Z' AND deleted_at IS NULL ORDER BY inicio"
medir "Reporte: turnos por dia y estado (zona de la sede)" \
	"SELECT DATE(CONVERT_TZ(inicio, '+00:00', '-03:00')) d, estado, COUNT(*) FROM turno WHERE organization_id = $ORG AND consultorio_id = $SEDE AND inicio >= '2026-11-01' AND inicio < '2027-06-01' GROUP BY d, estado" \
	"SELECT CAST(inicio AT TIME ZONE 'America/Argentina/Cordoba' AS date) d, estado, COUNT(*) FROM turno WHERE organization_id = $ORG AND consultorio_id = $SEDE AND inicio >= '2026-11-01Z' AND inicio < '2027-06-01Z' GROUP BY d, estado"
medir "Padron: busqueda 'gar' sin mayusculas ni tildes" \
	"SELECT * FROM persona WHERE organization_id = $ORG AND (apellido_clave LIKE '%gar%' OR nombre_clave LIKE '%gar%') ORDER BY apellido_clave, nombre_clave, id LIMIT 50" \
	"SELECT * FROM persona WHERE organization_id = $ORG AND (apellido_clave LIKE '%gar%' OR nombre_clave LIKE '%gar%') ORDER BY apellido_clave, nombre_clave, id LIMIT 50"
medir "Auditoria de la organizacion por periodo (pagina 1)" \
	"SELECT * FROM audit_event WHERE organization_id = $ORG AND occurred_at >= '2026-01-01' AND occurred_at <= '2027-01-01' ORDER BY occurred_at DESC, id DESC LIMIT 20" \
	"SELECT * FROM audit_event WHERE organization_id = $ORG AND occurred_at >= '2026-01-01Z' AND occurred_at <= '2027-01-01Z' ORDER BY occurred_at DESC, id DESC LIMIT 20"
medir "Historial de agenda (eventos de turnos por profesional)" \
	"SELECT * FROM audit_event WHERE organization_id = $ORG AND event_type IN ('TURNO_RESERVADO','TURNO_CANCELADO','TURNO_REPROGRAMADO') ORDER BY occurred_at DESC LIMIT 20" \
	"SELECT * FROM audit_event WHERE organization_id = $ORG AND event_type IN ('TURNO_RESERVADO','TURNO_CANCELADO','TURNO_REPROGRAMADO') ORDER BY occurred_at DESC LIMIT 20"
medir "Disponibilidad vigente de los profesionales de la sede" \
	"SELECT * FROM profesional_disponibilidad WHERE organization_id = $ORG AND consultorio_id = $SEDE AND active = 1 AND vigencia_desde <= '2026-11-20' AND (vigencia_hasta IS NULL OR vigencia_hasta > '2026-11-20')" \
	"SELECT * FROM profesional_disponibilidad WHERE organization_id = $ORG AND consultorio_id = $SEDE AND active AND vigencia_desde <= '2026-11-20' AND (vigencia_hasta IS NULL OR vigencia_hasta > '2026-11-20')"
