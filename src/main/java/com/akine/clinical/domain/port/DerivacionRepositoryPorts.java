package com.akine.clinical.domain.port;

import com.akine.clinical.domain.DerivacionClinica;
import com.akine.clinical.spi.OrigenDeParticipacion;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia del vinculo entre una participacion grupal y su contexto clinico (AKINE-08.04).
 *
 * <p><b>Ninguna firma resuelve por id pelado.</b> Todas llevan {@code organizationId} en el
 * {@code WHERE}, incluidas las de lectura por PK: un {@code SELECT} que solo filtra por id funciona
 * perfecto y es un agujero de aislamiento que ningun test de una etapa detecta, porque los tests de
 * una etapa usan un solo tenant.
 *
 * <p><b>No hay {@code delete}.</b> Ni el heredado se usa: una derivacion se revierte y la fila
 * queda (regla maestra 10). Que el puerto no lo ofrezca es la unica garantia real.
 */
public final class DerivacionRepositoryPorts {

	private DerivacionRepositoryPorts() {
	}

	/** Lecturas y escrituras de {@code derivacion_clinica}. */
	public interface DerivacionClinicaRepositoryPort {

		/**
		 * Guarda y sincroniza con la base en el acto.
		 *
		 * <p>Es {@code saveAndFlush} y no {@code save} por dos motivos que ya se pagaron: el
		 * {@code INSERT} tiene que chocar contra {@code uk_derivacion_destino} <b>aca</b> y no al
		 * commitear —si no, la segunda capa de idempotencia no se puede atrapar— y porque
		 * {@code save()} antes del flush devuelve la version vieja, y la operacion siguiente muere
		 * en un 409 que no le echa la culpa a nadie.
		 */
		DerivacionClinica saveAndFlush(DerivacionClinica derivacion);

		/** La derivacion por su id, dentro del tenant. */
		Optional<DerivacionClinica> findByIdInScope(long organizationId, long derivacionId);

		/**
		 * La derivacion <b>vigente</b> de esa participacion a ese Caso, si existe.
		 *
		 * <p>Es la primera capa de idempotencia de {@code registrar}: resuelve el doble submit sin
		 * intentar el {@code INSERT}. La segunda capa —el unique— hace falta igual, porque dos
		 * requests simultaneos pasan los dos por aca.
		 */
		Optional<DerivacionClinica> findVigenteAlCaso(
				long organizationId,
				OrigenDeParticipacion origen,
				long participacionId,
				long casoClinicoId);

		/**
		 * Todas las derivaciones de esa participacion, vigentes y revertidas, mas nueva primero.
		 *
		 * <p>Las revertidas viajan a proposito: "ya lo derivaron y lo deshicieron" es informacion
		 * distinta de "nunca lo derivaron", y la segunda no explica nada.
		 */
		List<DerivacionClinica> findDeLaParticipacion(
				long organizationId, OrigenDeParticipacion origen, long participacionId);
	}
}
