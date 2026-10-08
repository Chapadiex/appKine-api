package com.akine.person.application;

import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.Persona;
import com.akine.person.domain.exception.AdjuntoInactivoException;
import com.akine.person.domain.exception.AdjuntoNotAccessibleException;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.NumeroDeDocumentoTakenException;
import com.akine.person.domain.exception.OrdenInactivaException;
import com.akine.person.domain.exception.OrdenNotAccessibleException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordenes medicas de un paciente (M17, AKINE-03.06).
 *
 * <h2>1. Esta clase NO toma ningun lock, y es correcto</h2>
 *
 * <p>Dos ordenes solapadas del mismo paciente son <b>legitimas</b>: un paciente puede traer dos
 * prescripciones vigentes a la vez, de dos medicos distintos, y ninguna invalida a la otra. La
 * unica regla de unicidad que existe es el numero impreso, y eso <b>si</b> lo expresa un unique
 * —es una igualdad, no un solapamiento de intervalos—. Es la otra mitad de la leccion del Paquete
 * B: el lock es para lo que ningun indice puede decir, y usarlo donde alcanza un unique solo
 * agrega contencion.
 *
 * <h2>2. Vencida no es dada de baja, y ninguna de las dos borra nada</h2>
 *
 * <p>El vencimiento se calcula al leer y la fila no se toca nunca: "un documento vencido no
 * desaparece" es requisito de la etapa. La baja logica exige motivo y significa otra cosa —"esta
 * orden nunca debio cargarse"—. Mismo par que en M08 y M15.
 *
 * <h2>3. El documento escaneado se VINCULA, no se sube aca</h2>
 *
 * <p>RF-M17-002 y RN-M25-002. El adjunto lo sube {@code AdjuntoService} desde 03.02, con su
 * validacion de tipo y tamano, su {@code storage_key} opaca y su descarga autorizada llamada a
 * llamada. Esta clase solo guarda la FK. Un segundo mecanismo de carga significaria una segunda
 * validacion, una segunda ruta de descarga y una segunda superficie de path traversal.
 */
@Service
public class OrdenMedicaService {

	private static final Logger log = LoggerFactory.getLogger(OrdenMedicaService.class);

	private final OrdenMedicaRepositoryPort ordenes;
	private final CoberturaPacienteRepositoryPort coberturas;
	private final AdjuntoRepositoryPort adjuntos;
	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	@SuppressWarnings("java:S107")
	public OrdenMedicaService(
			OrdenMedicaRepositoryPort ordenes,
			CoberturaPacienteRepositoryPort coberturas,
			AdjuntoRepositoryPort adjuntos,
			PersonaRepositoryPort personas,
			PerfilPacienteRepositoryPort perfiles,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.ordenes = ordenes;
		this.coberturas = coberturas;
		this.adjuntos = adjuntos;
		this.personas = personas;
		this.perfiles = perfiles;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * El historial de ordenes del paciente, mas nuevas primero (RF-M17-006).
	 *
	 * <p>Trae las vencidas y las dadas de baja: sin ellas no se puede explicar con que papel se
	 * atendio al paciente el mes pasado, y "un documento vencido no desaparece".
	 *
	 * <p>Se autoriza con {@code paciente:read} (DP-22) y no con {@code paciente:manage}, mismo
	 * criterio que las demas lecturas del padron: el profesional que va a
	 * atender necesita saber si el paciente trajo la orden, y exigirle el permiso de gestion lo
	 * dejaria afuera. Es la "lectura justificada del profesional" que la etapa pide.
	 */
	@Transactional(readOnly = true)
	public List<OrdenView> listar(
			OperatingActor actor, long personaId, DocumentoEstadoFiltro estado, LocalDate fecha) {

		long organizationId =
				AutorizacionDePadron.exigirLecturaDelPadron(
						permissionGuard, actor, "Listar ordenes del paciente");
		exigirPersonaDelTenant(organizationId, personaId);

		DocumentoEstadoFiltro filtro = estado == null ? DocumentoEstadoFiltro.TODAS : estado;
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		return ordenes.historial(organizationId, personaId).stream()
				.filter(orden -> switch (filtro) {
					case ACTIVA -> orden.isActive();
					case INACTIVA -> !orden.isActive();
					case TODAS -> true;
				})
				.map(orden -> OrdenView.de(orden, contra))
				.toList();
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/** Registra la orden que el paciente presento (RF-M17-001). */
	@Transactional
	public OrdenView registrar(OperatingActor actor, long personaId, OrdenAltaCommand command) {
		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Registrar una orden medica");
		long organizationId = actor.contextOrganizationId();

		exigirPacienteVigente(organizationId, personaId, "cargar una orden medica");
		if (command.coberturaId() != null) {
			exigirCoberturaDelPaciente(organizationId, personaId, command.coberturaId());
		}

		OrdenMedica orden = new OrdenMedica(
				organizationId,
				personaId,
				actor.consultorioId(),
				command.coberturaId(),
				command.numero(),
				command.profesionalEmisor(),
				command.matriculaEmisor(),
				command.fechaEmision(),
				command.indicacion(),
				command.sesionesPrescriptas(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.observaciones());

		OrdenMedica creada = persistir(orden);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("fechaEmision", String.valueOf(creada.getFechaEmision()));
		detalles.put("vigenciaDesde", String.valueOf(creada.getVigenciaDesde()));
		if (creada.getNumero() != null) {
			detalles.put("numero", creada.getNumero());
		}
		auditar(AuditEvents.ORDEN_CREATED, creada, actor, null, "ACTIVA", null, detalles);

		log.info("Orden medica registrada: ordenId={} personaId={}", creada.getId(), personaId);
		return OrdenView.de(creada, LocalDate.now());
	}

	/**
	 * Edita los datos de la orden (RF-M17-001).
	 *
	 * <p>Una orden dada de baja no se edita: 409. Reabrir la ficha de algo dado de baja
	 * reescribiria el historico que la regla maestra 10 protege. Una orden <b>vencida</b> si se
	 * edita, y hace falta: corregir la fecha de vigencia mal tipeada es el caso normal.
	 */
	@Transactional
	public OrdenView editar(
			OperatingActor actor, long personaId, long ordenId, OrdenEdicionCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Editar una orden medica");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);
		OrdenMedica orden = cargar(organizationId, personaId, ordenId);
		exigirOperable(orden, "edicion");
		exigirVersion(orden.getVersion(), command.expectedVersion());

		orden.updateDatos(
				command.numero(),
				command.profesionalEmisor(),
				command.matriculaEmisor(),
				command.fechaEmision(),
				command.indicacion(),
				command.sesionesPrescriptas(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.observaciones());

		OrdenMedica guardada = persistir(orden);
		auditar(AuditEvents.ORDEN_UPDATED, guardada, actor, null, null, null, Map.of());
		return OrdenView.de(guardada, LocalDate.now());
	}

	/**
	 * Vincula un adjunto ya subido como el escaneo de la orden (RF-M17-002, RF-M25-006).
	 *
	 * <p>El adjunto tiene que ser <b>de la misma persona</b>. Vincular el documento de otro
	 * paciente lo volveria descargable desde esta ruta, y el control de acceso del adjunto —que
	 * hereda el de su persona, RN-M25-003— quedaria evadido por la puerta de al lado.
	 *
	 * <p>{@code adjuntoId} nulo desvincula. Es una operacion legitima: el operador subio el
	 * escaneo equivocado y lo saca sin tener que dar de baja la orden entera.
	 */
	@Transactional
	public OrdenView vincularDocumento(
			OperatingActor actor, long personaId, long ordenId, Long adjuntoId) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Vincular el documento de una orden");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);
		OrdenMedica orden = cargar(organizationId, personaId, ordenId);
		exigirOperable(orden, "vincular un documento");

		if (adjuntoId == null) {
			orden.desvincularDocumento();
		} else {
			orden.vincularDocumento(
					exigirAdjuntoVigente(organizationId, personaId, adjuntoId).getId());
		}

		OrdenMedica guardada = ordenes.save(orden);
		auditar(AuditEvents.ORDEN_DOCUMENTO_LINKED, guardada, actor, null, null, null,
				Map.of("adjuntoId", String.valueOf(adjuntoId)));

		log.info("Documento de orden actualizado: ordenId={} adjuntoId={}", ordenId, adjuntoId);
		return OrdenView.de(guardada, LocalDate.now());
	}

	/** Baja logica con motivo obligatorio. No borra nada (regla maestra 10). */
	@Transactional
	public OrdenView darDeBaja(
			OperatingActor actor, long personaId, long ordenId, String motivo) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Dar de baja una orden medica");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);
		OrdenMedica orden = cargar(organizationId, personaId, ordenId);
		exigirOperable(orden, "dar de baja");

		orden.deactivate(Instant.now(), exigirMotivo(motivo));
		OrdenMedica guardada = ordenes.save(orden);

		auditar(AuditEvents.ORDEN_DEACTIVATED, guardada, actor, "ACTIVA", "INACTIVA", motivo,
				Map.of());

		log.info("Orden medica dada de baja: ordenId={} personaId={}", ordenId, personaId);
		return OrdenView.de(guardada, LocalDate.now());
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	private void exigirPacienteVigente(long organizationId, long personaId, String operacion) {
		Persona persona = exigirPersonaDelTenant(organizationId, personaId);
		if (!persona.isActive()) {
			throw new PersonaInactivaException(personaId, operacion);
		}
		// RF-M07-010 sostenido desde M17: una Persona no es un Paciente, y cargarle una orden
		// medica a un contacto administrativo no significa nada. Reusa el mismo problemType que
		// 03.04 y 05.02, porque es la misma condicion y la misma accion correctiva.
		if (perfiles.buscarVigente(organizationId, personaId).isEmpty()) {
			log.info("Orden rechazada: la persona no es paciente. personaId={}", personaId);
			throw new PersonaSinPerfilPacienteException(personaId);
		}
	}

	private Persona exigirPersonaDelTenant(long organizationId, long personaId) {
		return personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
	}

	private CoberturaPaciente exigirCoberturaDelPaciente(
			long organizationId, long personaId, long coberturaId) {

		return coberturas
				.findByIdAndOrganizationIdAndPersonaId(coberturaId, organizationId, personaId)
				.orElseThrow(() -> new CoberturaNotAccessibleException(coberturaId));
	}

	/** El adjunto existe, es de esta persona y no esta dado de baja. */
	private AdjuntoAdministrativo exigirAdjuntoVigente(
			long organizationId, long personaId, long adjuntoId) {

		AdjuntoAdministrativo adjunto = adjuntos
				.buscarDeLaPersona(organizationId, personaId, adjuntoId)
				.orElseThrow(() -> new AdjuntoNotAccessibleException(adjuntoId));
		if (!adjunto.isActive()) {
			throw new AdjuntoInactivoException(adjuntoId);
		}
		return adjunto;
	}

	private OrdenMedica cargar(long organizationId, long personaId, long ordenId) {
		return ordenes.findByIdAndOrganizationIdAndPersonaId(ordenId, organizationId, personaId)
				.orElseThrow(() -> new OrdenNotAccessibleException(ordenId));
	}

	private static void exigirOperable(OrdenMedica orden, String operacion) {
		if (!orden.isOperable()) {
			log.info("Operacion sobre orden inactiva rechazada: ordenId={} operacion={}",
					orden.getId(), operacion);
			throw new OrdenInactivaException(orden.getId(), operacion);
		}
	}

	static void exigirVersion(long actual, long esperada) {
		if (actual != esperada) {
			throw new OptimisticLockingFailureException(
					"El documento fue modificado por otra operacion");
		}
	}

	/**
	 * Guarda con flush.
	 *
	 * <p>El unico unique que puede chocar es {@code uk_orden_numero_vigente}: el mismo numero de
	 * orden cargado dos veces vigente para el mismo paciente. Se traduce a 409 con el numero, que
	 * es lo que el operador tiene delante — a diferencia del choque de afiliado en 03.04, aca la
	 * fila que choca es siempre del MISMO paciente, asi que nombrarla no filtra nada.
	 */
	private OrdenMedica persistir(OrdenMedica orden) {
		try {
			return ordenes.saveAndFlush(orden);
		} catch (DataIntegrityViolationException choque) {
			log.info("Orden rechazada por unique de numero: personaId={}", orden.getPersonaId());
			throw new NumeroDeDocumentoTakenException("orden medica", orden.getNumero());
		}
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una orden medica exige un motivo declarado");
		}
		return motivo.strip();
	}

	@SuppressWarnings("java:S107")
	private void auditar(
			String eventType,
			OrdenMedica orden,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				orden.getOrganizationId(),
				// consultorioId NULL, mismo criterio que la cobertura: la orden es de la persona,
				// que pertenece a la ORGANIZACION. La sede del contexto autorizo la operacion pero
				// no es el alcance del hecho.
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_ORDEN,
				orden.getId(),
				previousState,
				newState,
				detalles,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
