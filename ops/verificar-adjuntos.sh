#!/usr/bin/env bash
# Cruza las filas de adjuntos de la base contra los binarios del almacenamiento (G-13).
#
#   AKINE_DB_*=... AKINE_ADJUNTOS_VOLUME=akine-var ops/verificar-adjuntos.sh
#
# Para cada fila de adjunto_administrativo y adjunto_clinico busca el archivo en
# <raiz>/<k[0:2]>/<k[2:4]>/<storage_key> —el layout de LocalFileSystemAdjuntoStorage y
# LocalFileSystemContenidoClinicoStorage— y compara su SHA-256 con checksum_sha256.
#
# Clasifica cada fila:
#   OK          esta y el checksum coincide
#   FALTA       no esta
#   CORRUPTO    esta y el checksum NO coincide
#   REPARABLE   la fila dice NO_DISPONIBLE pero el archivo esta y coincide (runbook R6)
#
# Imprime solo ids y conteos: ni nombres de archivo, ni personas, ni contenido.
# Sale con 1 si hay alguna fila DISPONIBLE en FALTA o CORRUPTO.

source "$(dirname "$0")/lib.sh"
db_init

filas="$(mysql_query "
	SELECT 'adjuntos', id, storage_key, checksum_sha256, estado FROM adjunto_administrativo
	UNION ALL
	SELECT 'adjuntos-clinicos', id, storage_key, checksum_sha256, estado FROM adjunto_clinico")"

total="$(printf '%s' "$filas" | grep -c . || true)"
log "verificando $total filas de adjuntos contra el almacenamiento"

# La comparacion corre en un contenedor con el almacenamiento montado: no hace falta que el host
# de operaciones vea el volumen.
resultado="$(printf '%s\n' "$filas" | en_adjuntos ro '
	while IFS=$'"'"'\t'"'"' read -r raiz id clave suma estado; do
		[ -z "$id" ] && continue
		f="/data/$raiz/${clave:0:2}/${clave:2:2}/$clave"
		if [ ! -f "$f" ]; then
			r=FALTA
		elif [ "$(sha256sum "$f" | cut -d" " -f1)" != "$suma" ]; then
			r=CORRUPTO
		elif [ "$estado" = NO_DISPONIBLE ]; then
			r=REPARABLE
		else
			r=OK
		fi
		printf "%s\t%s\t%s\t%s\n" "$r" "$raiz" "$id" "$estado"
	done
	# Huerfanos: binarios sin fila. Inofensivos (p. ej. subidos entre el dump y el tar), se cuentan.
	echo "ARCHIVOS	$(find /data -type f 2>/dev/null | wc -l)"
')"

contar() { printf '%s\n' "$resultado" | awk -F'\t' -v r="$1" '$1==r' | wc -l | tr -d ' '; }
ok="$(contar OK)"; falta="$(contar FALTA)"; corrupto="$(contar CORRUPTO)"; reparable="$(contar REPARABLE)"
archivos="$(printf '%s\n' "$resultado" | awk -F'\t' '$1=="ARCHIVOS"{print $2}')"
huerfanos=$(( archivos - ok - corrupto - reparable ))

log "OK=$ok FALTA=$falta CORRUPTO=$corrupto REPARABLE=$reparable archivos_en_disco=$archivos huerfanos=$huerfanos"

graves="$(printf '%s\n' "$resultado" | awk -F'\t' '($1=="FALTA"||$1=="CORRUPTO") && $4=="DISPONIBLE"')"
if [[ -n "$(printf '%s\n' "$resultado" | awk -F'\t' '$1=="FALTA"||$1=="CORRUPTO"')" ]]; then
	printf '%s\n' "$resultado" | awk -F'\t' '$1=="FALTA"||$1=="CORRUPTO"{printf "  %-9s %-18s id=%s estado=%s\n",$1,$2,$3,$4}' >&2
fi
if [[ "$reparable" != 0 ]]; then
	log "filas NO_DISPONIBLE cuyo binario volvio y coincide (ver runbook R6 para reponerlas):"
	printf '%s\n' "$resultado" | awk -F'\t' '$1=="REPARABLE"{printf "  %-18s id=%s\n",$2,$3}' >&2
fi
[[ -z "$graves" ]] || fail "hay adjuntos DISPONIBLES sin binario o con binario corrupto"
log "almacenamiento consistente con la base"
