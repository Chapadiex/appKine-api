package com.akine.offering.application;

import com.akine.offering.domain.PermissionCodes;
import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.exception.ServicioCodigoTakenException;
import com.akine.offering.domain.exception.ServicioNombreTakenException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.exception.ServicioYaInactivoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El catalogo de Servicios: QUE existe, no como lo presta nadie (M27/M06, regla maestra 14).
 *
 * <h2>Dos poblaciones desde V143 (ADR-0025)</h2>
 *
 * <p>Hasta el 09/11/2026 un {@link Servicio} era puramente global (ADR-0023) y solo lo daba de alta
 * la plataforma. Desde V143 un centro tambien da de alta servicios propios —"Yoga" en un centro que
 * no espera a que la plataforma lo cree—, en la misma tabla y con el mismo patron que el catalogo
 * clinico de {@code resource.application.CatalogoService}, cuya cabecera conviene leer:
 *
 * <pre>
 *                  GLOBAL (organization_id NULL)        PROPIO (organization_id = N)
 *   QUIEN LO VE    todo usuario de cualquier centro      solo los usuarios de N
 *   QUIEN LO MUTA  el rol de plataforma                  catalogo:manage en N (ORG_ADMIN, ADMIN de sede)
 * </pre>
 *
 * <p>Toda lectura arma la lista de duenios visibles ({@link #owners}) y filtra por
 * {@code owner_key}. Un administrador de plataforma ve el catalogo global y <b>nada</b> de ningun
 * centro: los servicios propios son informacion comercial del centro. Un servicio de otro centro
 * es 404, nunca 403, para no confirmar que existe.
 *
 * <p>COMO un centro presta un servicio —precio, duracion, cupo, modalidad efectiva— sigue viviendo
 * en {@code OfertaServicioConsultorio}. Esta clase no conoce ninguna oferta.
 *
 * <h2>La baja NO cascadea</h2>
 *
 * <p>Dar de baja un servicio no toca las ofertas que lo referencian (RF-M27-002, RN-M03-006): solo
 * impide crear ofertas NUEVAS, y eso lo controla {@code OfertaService} leyendo
 * {@link Servicio#isOperable()} por el puerto. Esta clase no recibe el puerto de ofertas y
 * {@link Servicio} no tiene relacion JPA hacia ellas: no hay por donde cascadear.
 */
@Service
public class ServicioService {

	/** Centinela del duenio "plataforma" en {@code owner_key}. Ningun id de organizacion vale 0. */
	public static final long OWNER_PLATAFORMA = 0L;

	private static final Logger log = LoggerFactory.getLogger(ServicioService.class);

	private final ServicioRepositoryPort servicios;
	private final AuditTrail auditTrail;
	private final PermissionGuard permissionGuard;

	public ServicioService(
			ServicioRepositoryPort servicios, AuditTrail auditTrail, PermissionGuard permissionGuard) {
		this.servicios = servicios;
		this.auditTrail = auditTrail;
		this.permissionGuard = permissionGuard;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Los servicios que el actor puede ver —el catalogo global y, si trabaja en un centro, los
	 * propios de su organizacion—, ordenados por nombre.
	 *
	 * <p>Devuelve los INACTIVOS cuando se piden explicitamente: RN-M03-006 exige que los historicos
	 * sigan resolviendo. Con el filtro por defecto ({@code ACTIVO}) un selector nunca ofrece un
	 * servicio dado de baja.
	 *
	 * <p>Leer no pide permiso aparte: cualquier usuario del centro necesita ver el catalogo para
	 * configurar ofertas, igual que antes de V143.
	 */
	@Transactional(readOnly = true)
	public List<ServicioView> buscar(OperatingActor actor, ServicioBusqueda filtros) {
		return servicios.buscarVisibles(owners(actor), filtros.patron(), filtros.activoFiltro()).stream()
				.map(ServicioView::de)
				.toList();
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de un Servicio (RF-M27-001). El alcance lo decide quien llama, no el cuerpo: el rol de
	 * plataforma crea en el catalogo global; un usuario de un centro, con {@code catalogo:manage},
	 * crea un servicio propio de su organizacion.
	 *
	 * <p><b>No consulta si el codigo existe antes de insertar</b>: entre un SELECT de comprobacion y
	 * el INSERT hay una ventana. El 409 sale de traducir la violacion de
	 * {@code uk_servicio_owner_codigo_vigente} / {@code uk_servicio_owner_nombre_vigente}, que llevan
	 * {@code owner_key}: el mismo codigo puede existir en el catalogo global y como servicio propio de
	 * un centro, y un codigo liberado por una baja logica se puede reusar.
	 *
	 * <p>Tampoco lleva {@code Idempotency-Key}: ver {@link ServicioAltaCommand}.
	 */
	@Transactional
	public ServicioView crear(OperatingActor actor, ServicioAltaCommand command) {
		Long duenio = actor.platformAdmin() ? null : exigirGestionDelCentro(actor);

		String codigo = normalizar(command.codigo(), "El codigo del servicio es obligatorio");
		String nombre = normalizar(command.nombre(), "El nombre del servicio es obligatorio");

		Servicio servicio = new Servicio(
				duenio,
				codigo,
				nombre,
				command.descripcion(),
				command.naturaleza(),
				command.modalidadDefault(),
				Boolean.TRUE.equals(command.requiereCasoClinicoDefault()),
				Boolean.TRUE.equals(command.generaRegistroClinicoDefault()));

		Servicio creado = persistir(servicio, codigo, nombre);

		Instant ahora = Instant.now();
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("codigo", codigo);
		detalles.put("alcance", ServicioView.alcanceDe(creado));
		detalles.put("naturaleza", creado.getNaturaleza().name());
		detalles.put("modalidadDefault", creado.getModalidadDefault().name());
		auditar(AuditEvents.SERVICIO_CREATED, creado, actor, null, "ACTIVO", null, detalles, ahora);

		log.info("Servicio creado: servicioId={} codigo={} organizationId={}",
				creado.getId(), codigo, duenio);
		return ServicioView.de(creado);
	}

	/**
	 * Edicion parcial: los campos {@code null} no se tocan. El codigo y el alcance no se editan —el
	 * codigo es la clave estable con la que las ofertas lo referencian—.
	 */
	@Transactional
	public ServicioView editar(
			OperatingActor actor, long servicioId, ServicioEdicionCommand command) {

		Servicio servicio = cargar(actor, servicioId);
		exigirGestionSobre(actor, servicio);
		exigirOperable(servicio, "editar");
		exigirVersion(servicio, command.expectedVersion());

		String nombre = command.nombre() == null
				? null
				: normalizar(command.nombre(), "El nombre del servicio es obligatorio");

		Map<String, String> detalles = cambios(servicio, nombre, command);
		servicio.updateDatos(
				nombre,
				command.descripcion(),
				command.naturaleza(),
				command.modalidadDefault(),
				command.requiereCasoClinicoDefault(),
				command.generaRegistroClinicoDefault());

		Servicio guardado;
		try {
			guardado = servicios.saveAndFlush(servicio);
		} catch (DataIntegrityViolationException choque) {
			if (esConflictoDeNombre(choque)) {
				log.info("Edicion de servicio rechazada por nombre repetido: servicioId={}",
						servicioId);
				throw new ServicioNombreTakenException(nombre);
			}
			throw choque;
		}

		Instant ahora = Instant.now();
		auditar(AuditEvents.SERVICIO_UPDATED, guardado, actor, null, null, null, detalles, ahora);
		return ServicioView.de(guardado);
	}

	/** Baja logica con motivo. No cascadea sobre las ofertas: ver la cabecera. */
	@Transactional
	public ServicioView darDeBaja(OperatingActor actor, long servicioId, String motivo) {
		Servicio servicio = cargar(actor, servicioId);
		exigirGestionSobre(actor, servicio);
		exigirOperable(servicio, "dar de baja");

		Instant ahora = Instant.now();
		servicio.deactivate(ahora, exigirMotivo(motivo));
		Servicio guardado = servicios.save(servicio);

		auditar(AuditEvents.SERVICIO_DEACTIVATED, guardado, actor,
				"ACTIVO", "INACTIVO", motivo, Map.of(), ahora);
		log.info("Servicio dado de baja: servicioId={}", servicioId);
		return ServicioView.de(guardado);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	/**
	 * Los duenios visibles: el catalogo global siempre, y la organizacion del contexto si la hay. El
	 * rol de plataforma no tiene contexto y ve solo el global.
	 */
	private static List<Long> owners(OperatingActor actor) {
		if (actor.platformAdmin() || actor.contextOrganizationId() == null) {
			return List.of(OWNER_PLATAFORMA);
		}
		return List.of(OWNER_PLATAFORMA, actor.contextOrganizationId());
	}

	private Servicio cargar(OperatingActor actor, long servicioId) {
		return servicios.findVisible(servicioId, owners(actor))
				.orElseThrow(() -> new ServicioNotAccessibleException(servicioId));
	}

	/** El global lo muta la plataforma; el propio, quien tenga {@code catalogo:manage} en su centro. */
	private void exigirGestionSobre(OperatingActor actor, Servicio servicio) {
		if (servicio.isGlobal()) {
			exigirPlataforma(actor);
		} else {
			// Un servicio propio de otro centro ni siquiera se cargo (404): aca el contexto coincide.
			exigirGestionDelCentro(actor);
		}
	}

	private static void exigirPlataforma(OperatingActor actor) {
		if (!actor.platformAdmin()) {
			log.info("Mutacion del catalogo global de servicios rechazada: accountId={}",
					actor.accountId());
			throw new AccessDeniedException(
					"El catalogo global de servicios lo administra unicamente la plataforma");
		}
	}

	/**
	 * {@code catalogo:manage} en el contexto del actor, el mismo permiso que el catalogo clinico
	 * propio (M06, DP-36): ORG_ADMIN con alcance de organizacion, ADMIN de sede con el de su sede.
	 *
	 * @return la organizacion del contexto, que es la duenia del servicio
	 */
	private long exigirGestionDelCentro(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CATALOGO_MANAGE,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	private static void exigirOperable(Servicio servicio, String operacion) {
		if (!servicio.isOperable()) {
			log.info("Operacion sobre servicio inactivo rechazada: servicioId={} operacion={}",
					servicio.getId(), operacion);
			throw new ServicioYaInactivoException(servicio.getId(), operacion);
		}
	}

	private static void exigirVersion(Servicio servicio, long esperada) {
		if (servicio.getVersion() != esperada) {
			throw new OptimisticLockingFailureException(
					"El servicio fue modificado por otra operacion");
		}
	}

	private Servicio persistir(Servicio servicio, String codigo, String nombre) {
		try {
			return servicios.saveAndFlush(servicio);
		} catch (DataIntegrityViolationException choque) {
			log.info("Alta de servicio rechazada por unique: codigo={}", codigo);
			if (esConflictoDeNombre(choque)) {
				throw new ServicioNombreTakenException(nombre);
			}
			throw new ServicioCodigoTakenException(codigo);
		}
	}

	/**
	 * Distingue la violacion del unique de nombre de la del codigo. MySQL nombra la constraint en el
	 * mensaje y Spring no lleva ningun campo estructurado con ella: se busca {@code _nombre_}
	 * —por {@code uk_servicio_owner_nombre_vigente}— y, si no aparece, se responde como codigo.
	 */
	private static boolean esConflictoDeNombre(DataIntegrityViolationException choque) {
		Throwable causa = choque.getMostSpecificCause();
		String mensaje = causa.getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains("_nombre_");
	}

	private static Map<String, String> cambios(
			Servicio servicio, String nombre, ServicioEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(servicio.getNombre())) {
			detalles.put("nombre", servicio.getNombre() + " -> " + nombre);
		}
		if (command.naturaleza() != null && command.naturaleza() != servicio.getNaturaleza()) {
			detalles.put("naturaleza", servicio.getNaturaleza() + " -> " + command.naturaleza());
		}
		if (command.modalidadDefault() != null
				&& command.modalidadDefault() != servicio.getModalidadDefault()) {
			detalles.put("modalidadDefault",
					servicio.getModalidadDefault() + " -> " + command.modalidadDefault());
		}
		if (command.requiereCasoClinicoDefault() != null
				&& command.requiereCasoClinicoDefault() != servicio.isRequiereCasoClinicoDefault()) {
			detalles.put("requiereCasoClinicoDefault",
					servicio.isRequiereCasoClinicoDefault() + " -> "
							+ command.requiereCasoClinicoDefault());
		}
		if (command.generaRegistroClinicoDefault() != null
				&& command.generaRegistroClinicoDefault()
						!= servicio.isGeneraRegistroClinicoDefault()) {
			detalles.put("generaRegistroClinicoDefault",
					servicio.isGeneraRegistroClinicoDefault() + " -> "
							+ command.generaRegistroClinicoDefault());
		}
		return detalles;
	}

	/**
	 * Auditoria en la transaccion del negocio. El servicio global va sin organizacion; el propio,
	 * con la suya, para que aparezca en la auditoria del centro.
	 */
	private void auditar(
			String eventType,
			Servicio servicio,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				servicio.getOrganizationId(),
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_SERVICIO,
				servicio.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	private static String normalizar(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip().replaceAll("\\s{2,}", " ");
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de un servicio: sin el, la auditoria "
							+ "no responde por que seis meses despues");
		}
		return motivo.strip();
	}
}
