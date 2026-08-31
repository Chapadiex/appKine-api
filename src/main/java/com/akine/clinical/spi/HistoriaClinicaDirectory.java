package com.akine.clinical.spi;

import java.util.Optional;

/**
 * El borde por donde {@code clinical} responde a los modulos que registran hechos clinicos.
 *
 * <h2>Quien lo va a usar, y por que existe antes que ellos</h2>
 *
 * <p>AKINE-06.01 (Sesion) es el consumidor previsto y no existe todavia. El puerto se declara
 * igual porque es lo que convierte a esta etapa en un cimiento y no en un cajon: la alternativa
 * —que otro modulo lea {@code clinical.domain} directo— viola la regla 1 de AGENT.md seccion 4 y
 * ArchUnit la rechaza, asi que el dia que llegue habria que escribir esto igual, con la diferencia
 * de que ya habria codigo escrito contra el atajo.
 *
 * <h2>Ninguno de estos metodos autoriza nada, y {@link #asegurar} menos que ninguno</h2>
 *
 * <p>Confian en que el llamador ya evaluo <b>su</b> permiso: quien registra una sesion tiene
 * {@code sesion:register}, y exigirle ademas {@code hc:write} para que la historia exista seria
 * duplicar la decision en dos modulos. El aislamiento de tenant SI se aplica: las dos firmas
 * exigen {@code organizationId} y ninguna resuelve por id pelado.
 *
 * <p>Lo que este puerto <b>no</b> expone es contenido clinico. Leer el resumen o los antecedentes
 * pasa por {@code HistoriaClinicaService}, que exige permiso, acceso justificado y deja auditoria;
 * si el contenido saliera por aca, esa auditoria tendria un agujero del tamaño del modulo que la
 * consuma.
 */
public interface HistoriaClinicaDirectory {

	/** La historia vigente de esa persona en esa organizacion, si existe. */
	Optional<HistoriaClinicaSnapshot> find(long organizationId, long personaId);

	/**
	 * La historia vigente de esa persona, abriendola si todavia no existe.
	 *
	 * <p>Es idempotente y lo garantiza el unique de la base, no un {@code SELECT} previo: dos
	 * llamadas simultaneas no producen dos historias.
	 *
	 * <p><b>Exige que la persona tenga perfil de paciente vigente</b> y falla si no lo tiene. Es
	 * la precondicion del recableado de DP-10, y tiene que estar aca y no solo en el camino
	 * humano: si el spi la salteara, el primer modulo que registre una prestacion sobre alguien
	 * que solo es "persona" le crearia historia clinica sin que nadie lo decidiera.
	 */
	HistoriaClinicaSnapshot asegurar(long organizationId, long personaId, long actorAccountId);
}
