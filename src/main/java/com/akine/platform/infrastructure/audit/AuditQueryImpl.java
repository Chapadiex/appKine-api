package com.akine.platform.infrastructure.audit;

import com.akine.platform.domain.AuditEvent;
import com.akine.platform.infrastructure.AuditEventRepository;
import com.akine.platform.spi.audit.AuditEventFilter;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.audit.AuditQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Lectura de la auditoria (RF-M24-002, RF-M24-003, RF-M24-004).
 *
 * <p>Es la contraparte de {@link AuditTrailImpl}: {@code platform} sigue siendo el unico
 * propietario de {@code audit_event}, y quien la lee lo hace por el puerto
 * {@link AuditQuery}, nunca tocando la tabla.
 *
 * <p><b>Este adaptador no autoriza.</b> Filtra por el {@code organizationId} del filtro y nada
 * mas. Quien decide si el actor puede leer eso —y con que alcance— es {@code organization},
 * dueño de la matriz de permisos; {@code platform} no puede evaluarlo sin depender de un modulo
 * funcional. La consecuencia para quien llame: el {@code organizationId} del filtro <b>tiene
 * que</b> salir del contexto validado del request, jamas de un parametro del cliente.
 *
 * <p>{@code @Component} y no {@code @Service}, igual que {@code AuditTrailImpl}:
 * {@code servicios_solo_en_application} reserva {@code @Service} para la capa de negocio, y esto
 * es un adaptador de persistencia.
 */
@Component
public class AuditQueryImpl implements AuditQuery {

	/**
	 * Deserializador propio del adaptador, simetrico al de la escritura.
	 *
	 * <p>La forma del JSON persistido es parte del dato auditado: leerlo con el
	 * {@code ObjectMapper} de la API haria que un cambio de configuracion de la capa web
	 * cambiara como se interpreta una fila de auditoria vieja.
	 */
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<Map<String, String>> MAPA_DE_DETALLES =
			new TypeReference<>() { };

	private final AuditEventRepository auditEventRepository;

	public AuditQueryImpl(AuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<AuditEventSummary> porEntidad(AuditEventFilter filtro, Pageable pageable) {
		return auditEventRepository
				.findAllByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
						filtro.organizationId(), filtro.entityType(), filtro.entityId(), pageable)
				.map(this::resumir);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<AuditEventSummary> porActor(AuditEventFilter filtro, Pageable pageable) {
		return auditEventRepository
				.findAllByOrganizationIdAndActorAccountIdOrderByOccurredAtDesc(
						filtro.organizationId(), filtro.actorAccountId(), pageable)
				.map(this::resumir);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Con {@code consultorioId} no nulo usa {@code ix_audit_event_org_loc_time}; sin el,
	 * {@code ix_audit_event_org_time}. Los dos indices los agrego la migracion V14 justamente
	 * porque los dos de V5 —por entidad y por actor— no sirven para un rango sobre
	 * {@code occurred_at} filtrando solo por organizacion: esta consulta era un scan del tenant
	 * entero.
	 */
	@Override
	@Transactional(readOnly = true)
	public Page<AuditEventSummary> porPeriodo(AuditEventFilter filtro, Pageable pageable) {
		if (filtro.consultorioId() != null) {
			return auditEventRepository
					.findAllByOrganizationIdAndConsultorioIdAndOccurredAtBetweenOrderByOccurredAtDesc(
							filtro.organizationId(), filtro.consultorioId(),
							filtro.desde(), filtro.hasta(), pageable)
					.map(this::resumir);
		}
		return auditEventRepository
				.findAllByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(
						filtro.organizationId(), filtro.desde(), filtro.hasta(), pageable)
				.map(this::resumir);
	}

	/**
	 * Traduce la entity al record del {@code spi}.
	 *
	 * <p>Un {@code details} ilegible no rompe la consulta: se devuelve vacio. Una fila de
	 * auditoria vieja con un JSON que ya no parsea sigue diciendo QUE paso, QUIEN lo hizo y
	 * SOBRE QUE, que es el 90 % de para lo que se consulta; hacer fallar la pagina entera por el
	 * detalle de una fila seria perder lo demas.
	 */
	private AuditEventSummary resumir(AuditEvent event) {
		return new AuditEventSummary(
				event.getId(),
				event.getOrganizationId(),
				event.getConsultorioId(),
				event.getActorAccountId(),
				event.getEventType(),
				event.getEntityType(),
				event.getEntityId(),
				event.getPreviousState(),
				event.getNewState(),
				detalles(event.getDetails()),
				event.getReason(),
				event.getCorrelationId(),
				event.getOccurredAt());
	}

	private Map<String, String> detalles(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return JSON.readValue(json, MAPA_DE_DETALLES);
		} catch (RuntimeException ilegible) {
			return Map.of();
		}
	}
}
