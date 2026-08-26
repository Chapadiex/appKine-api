package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Politica de calendario de una sede y calendario nacional de feriados (M05, RF-M05-004).
 *
 * <h2>Por que un feriado nacional no cierra el centro por si solo</h2>
 *
 * <p>{@code feriado} es una tabla GLOBAL y sin dueno (ADR-0022): es un hecho del calendario, no
 * una decision operativa. La decision de cada sede —cerrar o no ese dia— vive en
 * {@code consultorio_calendario}, y es esta clase la que la lee y la escribe. Un centro de guardia
 * atiende los feriados y el modelo tiene que poder decirlo.
 *
 * <h2>La fila de politica se crea a demanda, y nunca se borra</h2>
 *
 * <p>Una sede que nunca fue editada no tiene fila: {@link #ver} devuelve los valores por defecto
 * de V23 sin escribir nada. La fila aparece en la primera EDICION —o en el primer write de
 * disponibilidad de la sede, que la crea para poder bloquearla—.
 *
 * <p><b>Esa fila es tambien la que serializa los writes de disponibilidad de la sede</b>: es
 * sobre ella que {@code DisponibilidadService} y {@code ExcepcionService} toman su
 * {@code FOR UPDATE}. Por eso esta clase no ofrece ninguna baja: borrarla no dejaria a la sede
 * "sin politica", dejaria a los writes de esa sede sin punto de serializacion, y el sintoma
 * aparecerian meses despues como dos bloques solapados que nadie sabe como entraron.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Lectura {@code colaborador:read}, edicion {@code consultorio:manage}. Los mismos dos codigos
 * que el resto de M05, sin inventar ninguno.
 */
@Service
public class CalendarioService {

	private static final Logger log = LoggerFactory.getLogger(CalendarioService.class);

	private final CalendarioSedeRepositoryPort calendarios;
	private final FeriadoRepositoryPort feriados;
	private final ConsultorioDirectory consultorioDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public CalendarioService(
			CalendarioSedeRepositoryPort calendarios,
			FeriadoRepositoryPort feriados,
			ConsultorioDirectory consultorioDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.calendarios = calendarios;
		this.feriados = feriados;
		this.consultorioDirectory = consultorioDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	/**
	 * La politica de la sede y los feriados de su pais que caen en {@code [desde, hasta)}.
	 *
	 * <p><b>No crea la fila de politica.</b> Un GET que escribe convierte una consulta en una
	 * mutacion que nadie pidio y no puede ejecutarse en una transaccion de solo lectura; la sede
	 * sin fila lee sus valores por defecto y {@link CalendarioView#existePersistida()} lo dice.
	 *
	 * <p>El extremo superior de la busqueda de feriados se resta un dia porque
	 * {@code findByPaisAndFechaBetween} es INCLUSIVA en los dos extremos y esta ventana, como toda
	 * la de la etapa, es {@code [desde, hasta)}.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException si la ventana
	 *         supera el tope consultable (400)
	 * @throws IllegalArgumentException si la ventana esta invertida o vacia (400)
	 */
	@Transactional(readOnly = true)
	public CalendarioView ver(
			OperatingActor actor, long consultorioId, LocalDate desde, LocalDate hasta) {

		long organizationId = exigirLectura(actor, consultorioId);
		VentanaConsultable.exigirValida(desde, hasta);

		Optional<CalendarioSede> politica = calendarios.findByScope(organizationId, consultorioId);
		String pais = politica.map(CalendarioSede::getPais).orElse(CalendarioSede.PAIS_POR_DEFECTO);

		List<FeriadoView> delPeriodo =
				feriados.findByPaisAndFechaBetween(pais, desde, hasta.minusDays(1)).stream()
						.map(FeriadoView::de)
						.toList();

		return politica
				.map(fila -> CalendarioView.de(fila, delPeriodo))
				.orElseGet(() -> CalendarioView.porDefecto(consultorioId, delPeriodo));
	}

	// =================================================================================
	// Edicion
	// =================================================================================

	/**
	 * Edita la politica de calendario de la sede, creando la fila a demanda con los valores por
	 * defecto si es la primera vez.
	 *
	 * <p>Semantica de PATCH: cada valor {@code null} deja el campo como estaba. Un
	 * {@code cierraPorFeriado} nulo no es {@code false} —ese es el error que convierte "no toques
	 * la politica" en "abri todos los feriados"— y por eso viaja como {@link Boolean}.
	 *
	 * <p><b>No exige {@code expectedVersion}</b>, a diferencia de la edicion de un bloque. La fila
	 * se crea a demanda: un cliente que nunca la vio no tiene ninguna version que mandar, y
	 * exigirsela le impediria su primera edicion. Lo que serializa el acceso es el
	 * {@code FOR UPDATE} sobre esta misma fila, tomado antes de leerla. La contrapartida, dicha
	 * para que no sorprenda: dos administradores que editan la politica a la vez no producen 409;
	 * gana el segundo, y la auditoria conserva los dos cambios con su autor.
	 *
	 * <p>Se audita aunque sea un solo flag: apagar {@code cierraPorFeriado} abre de golpe todos
	 * los feriados del calendario para TODOS los profesionales de la sede, hacia adelante y hacia
	 * atras. Un cambio con ese alcance tiene que tener autor y fecha.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws IllegalArgumentException si el pais viene vacio (400)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CalendarioView actualizar(
			OperatingActor actor, long consultorioId, String pais, Boolean cierraPorFeriado) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Instant ahora = Instant.now();
		CalendarioSede politica = bloquearOCrear(organizationId, consultorioId);

		Map<String, String> detalles = cambios(politica, pais, cierraPorFeriado);
		politica.actualizarPolitica(pais, cierraPorFeriado);
		CalendarioSede guardada = calendarios.save(politica);

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				AuditEvents.CALENDARIO_SEDE_UPDATED,
				AuditEvents.ENTITY_CALENDARIO_SEDE,
				guardada.getId(),
				null,
				null,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));

		log.info("Politica de calendario actualizada: consultorioId={} cierraPorFeriado={}",
				consultorioId, guardada.isCierraPorFeriado());

		// Sin ventana no hay feriados que devolver: ver CalendarioView.
		return CalendarioView.de(guardada, List.of());
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	/**
	 * Toma el {@code FOR UPDATE} sobre la fila de politica, creandola a demanda.
	 *
	 * <p>Se intenta el lock ANTES de comprobar si la fila existe: preguntar primero con una
	 * lectura comun y bloquear despues es la escalada S-&gt;X que con dos transacciones en este
	 * camino termina en deadlock y no en espera. Misma ventana angosta declarada que en
	 * {@code DisponibilidadService}: dos primeros requests simultaneos de la misma sede insertan
	 * los dos y el unique rechaza a uno, que se resuelve con un reintento del cliente.
	 */
	private CalendarioSede bloquearOCrear(long organizationId, long consultorioId) {
		Optional<CalendarioSede> bloqueada = calendarios.lockByScope(organizationId, consultorioId);
		if (bloqueada.isPresent()) {
			return bloqueada.get();
		}
		calendarios.save(new CalendarioSede(organizationId, consultorioId));
		return calendarios.lockByScope(organizationId, consultorioId)
				.orElseThrow(() -> new IllegalStateException(
						"El calendario de la sede " + consultorioId + " no se pudo bloquear "
								+ "inmediatamente despues de crearlo"));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	private long exigirLectura(OperatingActor actor, long consultorioId) {
		if (actor.contextOrganizationId() == null) {
			log.info("Lectura de calendario sin contexto validado: accountId={}", actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		exigirSedeDelTenant(organizationId, consultorioId);

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.COLABORADOR_READ,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
		return organizationId;
	}

	/** Ver {@code DisponibilidadService#exigirContextoDeLaSede}: mismo control, mismo motivo. */
	private long exigirContextoDeLaSede(OperatingActor actor, long consultorioId) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Mutacion de calendario sin contexto validado: accountId={}", actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.consultorioId() != consultorioId) {
			log.info("Mutacion de calendario fuera del contexto del request: accountId={} consultorioId={}",
					actor.accountId(), consultorioId);
			throw new ConsultorioNotAccessibleException(consultorioId);
		}
		return actor.contextOrganizationId();
	}

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNotAccessibleException(consultorioId));
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private static Map<String, String> cambios(
			CalendarioSede politica, String pais, Boolean cierraPorFeriado) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (pais != null && !pais.strip().equals(politica.getPais())) {
			detalles.put("pais", politica.getPais() + " -> " + pais.strip());
		}
		if (cierraPorFeriado != null && cierraPorFeriado != politica.isCierraPorFeriado()) {
			detalles.put("cierraPorFeriado",
					politica.isCierraPorFeriado() + " -> " + cierraPorFeriado);
		}
		return detalles;
	}
}
