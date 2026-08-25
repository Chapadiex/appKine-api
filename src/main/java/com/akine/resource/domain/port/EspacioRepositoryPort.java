package com.akine.resource.domain.port;

import com.akine.resource.domain.Espacio;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los espacios. <b>Toda consulta filtra por {@code organizationId}</b>: un id de otro
 * tenant no debe resolver nunca (ADR-0004).
 */
public interface EspacioRepositoryPort {

	Espacio save(Espacio espacio);

	/**
	 * Guarda y FUERZA el flush.
	 *
	 * <p>Existe para el alta y para la edicion del nombre: la violacion de
	 * {@code uk_espacio_sede_name_vigente} tiene que manifestarse dentro del bloque que sabe
	 * traducirla a un 409, y no al cerrar la transaccion, donde ya no hay a quien avisarle y el
	 * advice generico responde 500.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA.</b> Ni una lectura,
	 * ni un {@code save}, ni la auditoria: la especificacion de JPA prohibe seguir usando un
	 * {@code EntityManager} tras un flush que fallo, y lo que sale de ahi es un
	 * {@code AssertionFailure} o un "Transaction marked as rollbackOnly" que convierte un 409
	 * legitimo en un 500. Ya costo caro dos veces en este repositorio.
	 */
	Espacio saveAndFlush(Espacio espacio);

	/**
	 * Un espacio por id, acotado al tenant y a la sede, <b>activo o no</b>.
	 *
	 * <p>Las tres columnas del filtro son necesarias y ninguna es redundante: sin
	 * {@code organizationId} un id ajeno resuelve; sin {@code consultorioId} un espacio de otra
	 * sede del mismo tenant respondería a una ruta que no le corresponde, y el frontend
	 * mostraria un box de la sede B dentro de la pantalla de la sede A.
	 */
	Optional<Espacio> findByIdAndOrganizationIdAndConsultorioId(
			Long id, Long organizationId, Long consultorioId);

	/**
	 * El mismo espacio, <b>tomando lock exclusivo sobre la fila</b>.
	 *
	 * <p>Es la PRIMERA sentencia de toda mutacion que decide contra un conteo externo —hoy la
	 * reduccion de capacidad—, y el orden importa: una lectura no bloqueante anterior fija el
	 * snapshot de la transaccion y el conteo posterior devuelve datos anteriores al commit del
	 * competidor <b>aunque el lock ya se haya adquirido</b>. El lock serializa el ACCESO, no la
	 * VISIBILIDAD. Es el mismo bug que {@code LimiteDePlanConcurrenteIT} encontro sobre el
	 * limite de plan y que {@code countActiveForShare} resuelve del lado de las sedes.
	 */
	Optional<Espacio> findByIdForUpdate(Long id, Long organizationId, Long consultorioId);

	/** Todos los espacios de la sede, activos e historicos, ordenados por nombre. */
	List<Espacio> findAllByOrganizationIdAndConsultorioIdOrderByNameAsc(
			Long organizationId, Long consultorioId);

	/** Solo los vigentes administrativamente. Base del selector que excluye inactivos. */
	List<Espacio> findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNameAsc(
			Long organizationId, Long consultorioId, boolean active);

	/**
	 * Espacios de la sede EN SERVICIO durante toda la ventana {@code [desde, hasta)}.
	 *
	 * <p>Aplica RN-M04-002 y la ventana operativa en la BASE y no en memoria: es la consulta
	 * que la agenda de F5 va a ejecutar por cada franja, y traer todo el catalogo para
	 * descartarlo en Java no escala. La cubre {@code ix_espacio_vigencia}.
	 */
	List<Espacio> findEnServicio(
			Long organizationId, Long consultorioId, Instant desde, Instant hasta);
}
