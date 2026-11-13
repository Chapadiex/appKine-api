package com.akine.platform.infrastructure;

import com.akine.platform.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Acceso a {@code audit_event}. <b>Solo lectura y alta.</b>
 *
 * <p>Extiende {@link Repository} y no {@code JpaRepository} por la misma razon que
 * {@code SubscriptionTransitionRepository}: heredar {@code delete} y {@code deleteAll} sobre
 * la tabla de auditoria haria que "append-only" dependiera de que nadie los llame. Aca esos
 * metodos no existen.
 *
 * <p>Es la contracara del contrato {@code platform.spi.audit.AuditTrail}: el {@code save} se
 * ejecuta DENTRO de la transaccion de negocio, y si falla, la operacion auditada no se
 * confirma.
 *
 * <p>Las consultas paginadas y sus permisos ({@code auditoria:read},
 * {@code auditoria:read-clinica}) los agrega 01.03; aca solo quedan las lecturas por tenant
 * que hacen falta para verificar la escritura.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

	/** Unica escritura permitida: agregar una fila. */
	AuditEvent save(AuditEvent event);

	Optional<AuditEvent> findById(Long id);

	/**
	 * DP-45: el refinamiento por texto que comparten las cuatro consultas. "Contiene" en el tipo
	 * de evento y en el tipo de entidad —la collation de la tabla ignora mayusculas y acentos—, y
	 * si el texto es un numero, igual al id de la entidad o al de la cuenta actora. Nunca mira el
	 * motivo ni los detalles: la lectura sin {@code auditoria:read-clinica} los redacta, y buscar
	 * en ellos dejaria adivinar lo redactado por coincidencia.
	 *
	 * <p>El orden NO esta en la consulta: lo trae el {@link Pageable}. Antes de DP-45 era
	 * {@code ORDER BY occurred_at DESC} fijo en el nombre del metodo, y el sort del pedido se
	 * agregaba DETRAS; el llamador pide hoy exactamente ese orden por defecto.
	 */
	String TEXTO = """
			   and (:patron is null
			        or e.eventType like :patron escape '\\'
			        or e.entityType like :patron escape '\\'
			        or e.entityId = :numero
			        or e.actorAccountId = :numero)
			""";

	/** Que le paso a una entidad. Usa {@code ix_audit_event_entity}. */
	@Query("select e from AuditEvent e where e.organizationId = :organizationId"
			+ " and e.entityType = :entityType and e.entityId = :entityId" + TEXTO)
	Page<AuditEvent> porEntidad(
			@Param("organizationId") Long organizationId,
			@Param("entityType") String entityType,
			@Param("entityId") Long entityId,
			@Param("patron") String patron,
			@Param("numero") Long numero,
			Pageable pageable);

	/** Que hizo un actor dentro de un tenant. Usa {@code ix_audit_event_actor}. */
	@Query("select e from AuditEvent e where e.organizationId = :organizationId"
			+ " and e.actorAccountId = :actorAccountId" + TEXTO)
	Page<AuditEvent> porActor(
			@Param("organizationId") Long organizationId,
			@Param("actorAccountId") Long actorAccountId,
			@Param("patron") String patron,
			@Param("numero") Long numero,
			Pageable pageable);

	/**
	 * Ventana temporal de un tenant, para investigar un incidente acotado (RF-M24-004). Los dos
	 * extremos incluidos, como el {@code Between} derivado que reemplaza.
	 *
	 * <p>Usa {@code ix_audit_event_org_time}, que agrego la migracion V14. Antes de esa
	 * migracion esta consulta era un scan del tenant entero: ninguno de los dos indices de V5
	 * —por entidad y por actor— sirve para un rango sobre {@code occurred_at} filtrando solo por
	 * organizacion, porque la columna del rango no queda en el prefijo del indice.
	 */
	@Query("select e from AuditEvent e where e.organizationId = :organizationId"
			+ " and e.occurredAt >= :desde and e.occurredAt <= :hasta" + TEXTO)
	Page<AuditEvent> porPeriodo(
			@Param("organizationId") Long organizationId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("patron") String patron,
			@Param("numero") Long numero,
			Pageable pageable);

	/**
	 * Ventana temporal acotada a una sede.
	 *
	 * <p>Es el alcance de {@code auditoria:read} para {@code CONSULTORIO_ADMIN}, que la matriz
	 * §6 define como "Consultorio". Usa {@code ix_audit_event_org_loc_time} (V14).
	 */
	@Query("select e from AuditEvent e where e.organizationId = :organizationId"
			+ " and e.consultorioId = :consultorioId"
			+ " and e.occurredAt >= :desde and e.occurredAt <= :hasta" + TEXTO)
	Page<AuditEvent> porPeriodoEnSede(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("patron") String patron,
			@Param("numero") Long numero,
			Pageable pageable);

	/**
	 * Historial de agenda de UNA PERSONA (V134, V139): los eventos de esos tipos cuyo profesional —o el
	 * de antes, en una reprogramacion— es alguna de sus memberships; los eventos de sede que el llamador
	 * ya resolvio que la tocan ({@code idsSede}); y, con {@code unificar}, un cambio de sede como UNA
	 * fila. Usa {@code ix_audit_event_agenda}, {@code ix_audit_event_agenda_anterior},
	 * {@code ix_audit_event_entity} e {@code ix_audit_event_agenda_turno_origen}.
	 *
	 * <p>Unificar el traslado: la cancelacion del turno original que tiene un {@code traslado} se
	 * absorbe, la reserva que lleva {@code turnoOrigenId} tambien, y el {@code traslado} aparece ademas
	 * para el profesional de destino (el de esa reserva). Todo en SQL, para que el paginado y el total
	 * sigan siendo exactos.
	 *
	 * <p>Aca si se busca en el motivo: quien lee este historial ya puede ver la agenda de esa persona
	 * y no hay nada redactado que adivinar (a diferencia de {@link #TEXTO}).
	 *
	 * @param todasLasSedes {@code true} ignora {@code sedes}: el lector alcanza la organizacion entera
	 */
	@Query("select e from AuditEvent e where e.organizationId = :organizationId"
			+ " and ("
			+ "   (e.eventType in :tipos"
			+ "    and (e.agendaMembershipRef in :memberships or e.agendaMembershipAnteriorRef in :memberships)"
			+ "    and (:todasLasSedes = true or e.consultorioId in :sedes))"
			+ "   or (:conSede = true and e.entityType = :entidadSede and e.entityId in :idsSede"
			+ "    and e.eventType in :tiposSede"
			+ "    and (:todasLasSedes = true or e.consultorioId in :sedes))"
			+ "   or (:unificar = true and e.eventType = :traslado and e.eventType in :tipos and exists ("
			+ "    select r.id from AuditEvent r where r.organizationId = e.organizationId"
			+ "     and r.eventType = :reserva and r.agendaTurnoOrigenRef = cast(e.entityId as String)"
			+ "     and r.agendaMembershipRef in :memberships"
			+ "     and (:todasLasSedes = true or r.consultorioId in :sedes or e.consultorioId in :sedes)))"
			+ " )"
			+ " and (:unificar = false or e.eventType <> :cancelacion or not exists ("
			+ "    select t.id from AuditEvent t where t.organizationId = e.organizationId"
			+ "     and t.eventType = :traslado and t.entityType = e.entityType and t.entityId = e.entityId))"
			+ " and (:unificar = false or e.eventType <> :reserva or e.agendaTurnoOrigenRef is null)"
			+ " and e.occurredAt >= :desde and e.occurredAt < :hasta"
			+ " and (:patron is null or e.reason like :patron escape '\\'"
			+ "      or e.eventType like :patron escape '\\')")
	Page<AuditEvent> deAgenda(
			@Param("organizationId") Long organizationId,
			@Param("memberships") java.util.Collection<String> memberships,
			@Param("tipos") java.util.Collection<String> tipos,
			@Param("todasLasSedes") boolean todasLasSedes,
			@Param("sedes") java.util.Collection<Long> sedes,
			@Param("conSede") boolean conSede,
			@Param("entidadSede") String entidadSede,
			@Param("idsSede") java.util.Collection<Long> idsSede,
			@Param("tiposSede") java.util.Collection<String> tiposSede,
			@Param("unificar") boolean unificar,
			@Param("traslado") String traslado,
			@Param("cancelacion") String cancelacion,
			@Param("reserva") String reserva,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("patron") String patron,
			Pageable pageable);

	/**
	 * Las filas absorbidas de los traslados de esos turnos de origen: su cancelacion y la reserva del
	 * turno nuevo (V139). Para el detalle de la fila visible, no para paginar.
	 */
	@Query("select e from AuditEvent e where e.organizationId = :organizationId"
			+ " and ((e.eventType = :cancelacion and e.entityType = :entidad and e.entityId in :turnos)"
			+ "   or (e.eventType = :reserva and e.agendaTurnoOrigenRef in :turnosTexto))"
			+ " order by e.occurredAt asc, e.id asc")
	java.util.List<AuditEvent> absorbidasDeTraslados(
			@Param("organizationId") Long organizationId,
			@Param("cancelacion") String cancelacion,
			@Param("reserva") String reserva,
			@Param("entidad") String entidad,
			@Param("turnos") java.util.Collection<Long> turnos,
			@Param("turnosTexto") java.util.Collection<String> turnosTexto);
}
