package com.akine.person.application;

import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.AccionSobreAutorizacion;
import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.exception.AdjuntoInactivoException;
import com.akine.person.domain.exception.AdjuntoNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionInactivaException;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSuperpuestaException;
import com.akine.person.domain.exception.AutorizacionTransicionNoPermitidaException;
import com.akine.person.domain.exception.CoberturaInactivaException;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.NumeroDeDocumentoTakenException;
import com.akine.person.domain.exception.OrdenInactivaException;
import com.akine.person.domain.exception.OrdenNotAccessibleException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionPersonaLockRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Autorizaciones de un financiador para un paciente (M17, AKINE-03.06).
 *
 * <h2>1. Congela el convenio, y esa es la unica llamada al catalogo que hace</h2>
 *
 * <p>{@code contracting.spi} separa a proposito dos cosas, y esta clase usa <b>solo la segunda</b>:
 *
 * <pre>
 *   resolver(...)    LECTURA VIVA, para DECIDIR. La usa ElegibilidadAdministrativaService.
 *   congelar(...)    COPIA, para GUARDAR. Es lo unico que esta clase llama, y solo en el ALTA.
 * </pre>
 *
 * <p>Despues del alta, <b>ninguna lectura de autorizacion vuelve a tocar {@code contracting}</b>.
 * Si lo hiciera, cambiar las exigencias del convenio manana reescribiria con que reglas se
 * autorizo al paciente el mes pasado. Es el mismo patron con que 03.04 congelo el plan y 07.01 el
 * precio de la oferta, y la ausencia de llamadas <b>es</b> la garantia.
 *
 * <p><b>La copia es opcional</b>, que es la diferencia con la cobertura: sin convenio resoluble el
 * alta igual entra, sin snapshot. Ver {@link Autorizacion}.
 *
 * <h2>2. La regla temporal la hace cumplir un LOCK, y solo la toman dos operaciones</h2>
 *
 * <p>Dos autorizaciones APROBADAS de la misma cobertura y practica no se pueden solapar: contarian
 * el saldo dos veces. Ningun unique de MySQL 8.4 lo expresa. Lo verifica esta clase bajo el lock de
 * {@code autorizacion_persona_lock}, en <b>{@code READ_COMMITTED}</b> —con {@code REPEATABLE READ}
 * InnoDB fija la foto antes del lock y las dos pasarian—, y tomandolo <b>antes</b> de leer nada
 * —leer primero y bloquear despues es una escalada S→X entre transacciones simetricas—.
 *
 * <p><b>Lo toman el alta que nace APROBADA y {@link #resolver}, y ninguna otra operacion.</b> Una
 * autorizacion PENDIENTE no autoriza ninguna cantidad y no puede crear el conflicto; la edicion de
 * una PENDIENTE tampoco; y la baja quita una fila del conjunto, que nunca produce un solapamiento.
 * Serializar esas tres agregaria contencion sin proteger ninguna invariante. La edicion de una
 * <b>APROBADA</b> si lo toma: mover sus fechas es exactamente el mismo riesgo que aprobarla.
 *
 * <h2>3. Esta clase registra y habilita. NO consume (RN-M17-001)</h2>
 *
 * <p>{@code cantidadConsumida} nace en cero y <b>nada de aca la incrementa</b>. Consumir es
 * RF-M17-004 y llega con la integracion clinica. El saldo que se devuelve hoy es el inicial.
 */
@Service
public class AutorizacionService {

	private static final Logger log = LoggerFactory.getLogger(AutorizacionService.class);

	private final AutorizacionRepositoryPort autorizaciones;
	private final AutorizacionPersonaLockRepositoryPort candados;
	private final AutorizacionLockIniciador iniciador;
	private final OrdenMedicaRepositoryPort ordenes;
	private final CoberturaPacienteRepositoryPort coberturas;
	private final AdjuntoRepositoryPort adjuntos;
	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;
	private final ArancelDirectory aranceles;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	@SuppressWarnings("java:S107")
	public AutorizacionService(
			AutorizacionRepositoryPort autorizaciones,
			AutorizacionPersonaLockRepositoryPort candados,
			AutorizacionLockIniciador iniciador,
			OrdenMedicaRepositoryPort ordenes,
			CoberturaPacienteRepositoryPort coberturas,
			AdjuntoRepositoryPort adjuntos,
			PersonaRepositoryPort personas,
			PerfilPacienteRepositoryPort perfiles,
			ArancelDirectory aranceles,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.autorizaciones = autorizaciones;
		this.candados = candados;
		this.iniciador = iniciador;
		this.ordenes = ordenes;
		this.coberturas = coberturas;
		this.adjuntos = adjuntos;
		this.personas = personas;
		this.perfiles = perfiles;
		this.aranceles = aranceles;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * El historial de autorizaciones del paciente, mas nuevas primero (RF-M17-003, RF-M17-006).
	 *
	 * <p>Cada fila viaja con {@code saldo}, {@code vencida}, {@code agotada} y
	 * {@code diasParaVencer} calculados contra la fecha que se pregunta: es lo que le permite al
	 * panel administrativo mostrar los vencimientos sin que exista ningun job que mueva estados.
	 *
	 * <p>Se autoriza con {@code paciente:read} (AKINE-DU-6, DP-22), como el resto de las lecturas
	 * del padron.
	 */
	@Transactional(readOnly = true)
	public List<AutorizacionView> listar(
			OperatingActor actor, long personaId, DocumentoEstadoFiltro estado, LocalDate fecha) {

		long organizationId =
				AutorizacionDePadron.exigirLecturaDelPadron(
						permissionGuard, actor, "Listar autorizaciones del paciente");
		exigirPersonaDelTenant(organizationId, personaId);

		DocumentoEstadoFiltro filtro = estado == null ? DocumentoEstadoFiltro.TODAS : estado;
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		return autorizaciones.historial(organizationId, personaId).stream()
				.filter(autorizacion -> switch (filtro) {
					case ACTIVA -> autorizacion.isActive();
					case INACTIVA -> !autorizacion.isActive();
					case TODAS -> true;
				})
				.map(autorizacion -> AutorizacionView.de(autorizacion, contra))
				.toList();
	}

	/** Una autorizacion con su saldo (RF-M17-003). */
	@Transactional(readOnly = true)
	public AutorizacionView ver(
			OperatingActor actor, long personaId, long autorizacionId, LocalDate fecha) {

		long organizationId =
				AutorizacionDePadron.exigirLecturaDelPadron(
						permissionGuard, actor, "Consultar el saldo autorizado");
		exigirPersonaDelTenant(organizationId, personaId);

		return AutorizacionView.de(
				cargar(organizationId, personaId, autorizacionId),
				fecha == null ? LocalDate.now() : fecha);
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Registra la autorizacion (RF-M17-001).
	 *
	 * <p>El convenio se congela contra {@code vigenciaDesde} y no contra hoy: la pregunta que hay
	 * que contestar es que exigia el convenio el dia en que la autorizacion empieza a valer.
	 *
	 * <p>Nace PENDIENTE salvo que se declare APROBADA. Solo en ese segundo caso se toma el lock y
	 * se valida el solapamiento: una PENDIENTE no autoriza ninguna cantidad y no puede duplicar
	 * ningun saldo.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AutorizacionView registrar(
			OperatingActor actor, long personaId, AutorizacionAltaCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Registrar una autorizacion");
		long organizationId = actor.contextOrganizationId();
		long consultorioId = actor.consultorioId();

		exigirPacienteVigente(organizationId, personaId, "cargar una autorizacion");

		long coberturaId = exigirNoNulo(
				command.coberturaId(),
				"La autorizacion necesita la cobertura contra la que el financiador la otorgo");
		long practicaId = exigirNoNulo(
				command.practicaId(), "La autorizacion necesita la practica autorizada");

		CoberturaPaciente cobertura =
				exigirCoberturaOperable(organizationId, personaId, coberturaId);
		Long ordenMedicaId = resolverOrden(organizationId, personaId, command.ordenMedicaId());

		LocalDate desde = exigirNoNuloRef(
				command.vigenciaDesde(), "La autorizacion necesita una fecha de inicio");
		EstadoAutorizacion estadoInicial = command.estadoInicial() == null
				? EstadoAutorizacion.PENDIENTE
				: command.estadoInicial();

		Autorizacion autorizacion = new Autorizacion(
				organizationId,
				personaId,
				consultorioId,
				coberturaId,
				ordenMedicaId,
				practicaId,
				command.numero(),
				estadoInicial,
				command.cantidadAutorizada(),
				desde,
				command.vigenciaHasta(),
				congelarConvenio(organizationId, consultorioId, cobertura, practicaId, desde)
						.orElse(null),
				command.observaciones());

		if (estadoInicial.habilita()) {
			// PASO 0. En su PROPIA transaccion: crearla dentro de esta deadlockea entre las
			// primeras autorizaciones concurrentes de una persona. Ver AutorizacionLockIniciador.
			iniciador.asegurar(organizationId, personaId);
			// PASO 1. Antes de leer nada de autorizaciones. Ver la cabecera de la clase.
			BloqueoDeAutorizaciones.tomar(candados, organizationId, personaId);
			// PASO 2. Bajo el lock. Ningun unique puede expresar esta regla.
			exigirSinSolapamiento(
					organizationId, coberturaId, practicaId, null, desde, command.vigenciaHasta());
		}

		Autorizacion creada = persistir(autorizacion);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("numero", creada.getNumero());
		detalles.put("coberturaId", String.valueOf(coberturaId));
		detalles.put("practicaId", String.valueOf(practicaId));
		detalles.put("vigenciaDesde", String.valueOf(creada.getVigenciaDesde()));
		if (creada.getCantidadAutorizada() != null) {
			detalles.put("cantidadAutorizada", String.valueOf(creada.getCantidadAutorizada()));
		}
		detalles.put("convenioCongelado", String.valueOf(creada.tieneConvenioCongelado()));
		auditar(AuditEvents.AUTORIZACION_CREATED, creada, actor, null,
				creada.getEstado().name(), null, detalles);

		log.info("Autorizacion registrada: autorizacionId={} personaId={} estado={}",
				creada.getId(), personaId, creada.getEstado());
		return AutorizacionView.de(creada, LocalDate.now());
	}

	/**
	 * Edita los datos NO historicos de la autorizacion.
	 *
	 * <p>La cobertura, la practica, la sede y las seis columnas congeladas son inmutables y el
	 * comando ni siquiera las ofrece. El estado tampoco esta: se mueve con {@link #resolver}.
	 *
	 * <p><b>Toma el lock solo si la autorizacion ya esta APROBADA</b>, porque mover las fechas de
	 * una aprobada es exactamente el mismo riesgo que aprobarla. Editar una PENDIENTE no puede
	 * crear un solapamiento.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AutorizacionView editar(
			OperatingActor actor,
			long personaId,
			long autorizacionId,
			AutorizacionEdicionCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Editar una autorizacion");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);

		Autorizacion autorizacion = cargar(organizationId, personaId, autorizacionId);
		exigirOperable(autorizacion, "edicion");
		OrdenMedicaService.exigirVersion(autorizacion.getVersion(), command.expectedVersion());

		boolean serializa = autorizacion.getEstado().habilita();
		if (serializa) {
			iniciador.asegurar(organizationId, personaId);
			BloqueoDeAutorizaciones.tomar(candados, organizationId, personaId);
			// Recargar bajo el lock: entre la lectura de arriba y el lock, otra transaccion pudo
			// haberla resuelto. La version ya se comprobo, asi que si cambio esta escritura muere
			// en el bloqueo optimista del flush, que es donde tiene que morir.
			autorizacion = cargar(organizationId, personaId, autorizacionId);
		}

		Long ordenMedicaId = resolverOrden(organizationId, personaId, command.ordenMedicaId());
		autorizacion.updateDatos(
				command.numero(),
				ordenMedicaId,
				command.cantidadAutorizada(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.observaciones());

		if (serializa) {
			exigirSinSolapamiento(
					organizationId,
					autorizacion.getCoberturaId(),
					autorizacion.getPracticaId(),
					autorizacion.getId(),
					autorizacion.getVigenciaDesde(),
					autorizacion.getVigenciaHasta());
		}

		Autorizacion guardada = persistir(autorizacion);
		auditar(AuditEvents.AUTORIZACION_UPDATED, guardada, actor, null, null, null, Map.of());
		return AutorizacionView.de(guardada, LocalDate.now());
	}

	/**
	 * Aplica la respuesta del financiador: aprobar, observar o rechazar (RF-M17-001).
	 *
	 * <p>Es una ACCION y no una asignacion de estado: ver {@link AccionSobreAutorizacion}. Desde
	 * APROBADA o RECHAZADA no se sale, y eso es tambien lo que resuelve el caso borde
	 * <b>"aprobacion concurrente"</b>: el segundo hilo encuentra la autorizacion ya resuelta y
	 * recibe 409, no un 200 que aprueba dos veces.
	 *
	 * <p><b>Aprobar puede otorgar MENOS de lo pedido</b> —autorizacion parcial— y para una ventana
	 * mas corta. Lo que vale es lo que el financiador concedio, no lo que el centro pidio.
	 *
	 * <p>Solo APROBAR toma el lock. Observar y rechazar no habilitan nada y no pueden duplicar
	 * ningun saldo.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AutorizacionView resolver(
			OperatingActor actor,
			long personaId,
			long autorizacionId,
			ResolucionDeAutorizacionCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Resolver una autorizacion");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);

		AccionSobreAutorizacion accion = exigirNoNuloRef(
				command.accion(), "La resolucion necesita declarar la accion");

		if (accion == AccionSobreAutorizacion.APROBAR) {
			// El lock ANTES de leer la autorizacion que se va a modificar: leerla primero y
			// bloquear despues es la escalada S→X que este proyecto ya documento.
			iniciador.asegurar(organizationId, personaId);
			BloqueoDeAutorizaciones.tomar(candados, organizationId, personaId);
		}

		Autorizacion autorizacion = cargar(organizationId, personaId, autorizacionId);
		exigirOperable(autorizacion, "ser resuelta");
		OrdenMedicaService.exigirVersion(autorizacion.getVersion(), command.expectedVersion());

		EstadoAutorizacion previo = autorizacion.getEstado();
		if (!previo.admiteResolucion()) {
			log.info("Resolucion rechazada: autorizacionId={} estado={} accion={}",
					autorizacionId, previo, accion);
			throw new AutorizacionTransicionNoPermitidaException(
					autorizacionId, previo.name(), accion.name());
		}

		autorizacion.resolver(
				accion,
				command.motivo(),
				command.cantidadAutorizada(),
				command.vigenciaDesde(),
				command.vigenciaHasta());

		if (accion == AccionSobreAutorizacion.APROBAR) {
			exigirSinSolapamiento(
					organizationId,
					autorizacion.getCoberturaId(),
					autorizacion.getPracticaId(),
					autorizacion.getId(),
					autorizacion.getVigenciaDesde(),
					autorizacion.getVigenciaHasta());
		}

		Autorizacion guardada = persistir(autorizacion);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("accion", accion.name());
		if (guardada.getCantidadAutorizada() != null) {
			detalles.put("cantidadAutorizada", String.valueOf(guardada.getCantidadAutorizada()));
		}
		auditar(AuditEvents.AUTORIZACION_RESUELTA, guardada, actor, previo.name(),
				guardada.getEstado().name(), command.motivo(), detalles);

		log.info("Autorizacion resuelta: autorizacionId={} {} -> {}",
				autorizacionId, previo, guardada.getEstado());
		return AutorizacionView.de(guardada, LocalDate.now());
	}

	/**
	 * Vincula un adjunto ya subido como comprobante (RF-M17-002, RF-M25-006).
	 *
	 * <p>Tiene que ser de la misma persona: vincular el documento de otro paciente lo volveria
	 * descargable desde esta ruta y evadiria el control de acceso que el adjunto hereda de su
	 * persona (RN-M25-003). {@code null} desvincula.
	 */
	@Transactional
	public AutorizacionView vincularDocumento(
			OperatingActor actor, long personaId, long autorizacionId, Long adjuntoId) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Vincular el comprobante de una autorizacion");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);
		Autorizacion autorizacion = cargar(organizationId, personaId, autorizacionId);
		exigirOperable(autorizacion, "vincular un documento");

		if (adjuntoId == null) {
			autorizacion.desvincularDocumento();
		} else {
			autorizacion.vincularDocumento(
					exigirAdjuntoVigente(organizationId, personaId, adjuntoId).getId());
		}

		Autorizacion guardada = autorizaciones.save(autorizacion);
		auditar(AuditEvents.AUTORIZACION_DOCUMENTO_LINKED, guardada, actor, null, null, null,
				Map.of("adjuntoId", String.valueOf(adjuntoId)));

		return AutorizacionView.de(guardada, LocalDate.now());
	}

	/**
	 * Baja logica con motivo obligatorio.
	 *
	 * <p><b>No toma el lock</b>: quitar una fila del conjunto activo nunca puede crear un
	 * solapamiento. Mismo criterio que la baja de cobertura en 03.04 y la de convenio en 03.05.
	 *
	 * <p>Dar de baja no es rechazar. Rechazar es la respuesta del financiador y deja la
	 * autorizacion viva y consultable; dar de baja es "esta autorizacion nunca debio cargarse".
	 */
	@Transactional
	public AutorizacionView darDeBaja(
			OperatingActor actor, long personaId, long autorizacionId, String motivo) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Dar de baja una autorizacion");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaDelTenant(organizationId, personaId);
		Autorizacion autorizacion = cargar(organizationId, personaId, autorizacionId);
		exigirOperable(autorizacion, "dar de baja");

		autorizacion.deactivate(Instant.now(), exigirMotivo(motivo));
		Autorizacion guardada = autorizaciones.save(autorizacion);

		auditar(AuditEvents.AUTORIZACION_DEACTIVATED, guardada, actor, "ACTIVA", "INACTIVA",
				motivo, Map.of());

		log.info("Autorizacion dada de baja: autorizacionId={} personaId={}",
				autorizacionId, personaId);
		return AutorizacionView.de(guardada, LocalDate.now());
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * La copia congelada del convenio. <b>La unica llamada al catalogo de todo este servicio.</b>
	 *
	 * <p>Devuelve vacio para una cobertura PARTICULAR —no hay financiador— y tambien cuando no hay
	 * convenio vigente o la practica no tiene arancel cargado. Las tres son estados legitimos: la
	 * autorizacion se registra igual, sin snapshot. Ver {@link Autorizacion}.
	 */
	private Optional<ArancelCongelado> congelarConvenio(
			long organizationId,
			long consultorioId,
			CoberturaPaciente cobertura,
			long practicaId,
			LocalDate fecha) {

		if (cobertura.getTipo() != TipoCobertura.FINANCIADA) {
			return Optional.empty();
		}
		return aranceles.congelar(
				organizationId,
				consultorioId,
				cobertura.getFinanciadorId(),
				cobertura.getPlanId(),
				practicaId,
				fecha);
	}

	/**
	 * No hay otra autorizacion APROBADA de la misma cobertura y practica con la vigencia solapada.
	 *
	 * <p>Se llama <b>siempre bajo el lock</b>. Fuera de el, dos transacciones leerian las dos "no
	 * hay conflicto" antes de que ninguna escriba, y las dos pasarian.
	 */
	@SuppressWarnings("java:S107")
	private void exigirSinSolapamiento(
			long organizationId,
			long coberturaId,
			long practicaId,
			Long excluirId,
			LocalDate desde,
			LocalDate hasta) {

		autorizaciones.aprobadasDe(organizationId, coberturaId, practicaId).stream()
				.filter(otra -> !otra.getId().equals(excluirId))
				.filter(otra -> otra.seSolapaCon(desde, hasta))
				.findFirst()
				.ifPresent(otra -> {
					log.info("Autorizacion rechazada por solapamiento: coberturaId={} "
							+ "practicaId={} contra={}", coberturaId, practicaId, otra.getId());
					throw new AutorizacionSuperpuestaException(otra.getId());
				});
	}

	private void exigirPacienteVigente(long organizationId, long personaId, String operacion) {
		Persona persona = exigirPersonaDelTenant(organizationId, personaId);
		if (!persona.isActive()) {
			throw new PersonaInactivaException(personaId, operacion);
		}
		if (perfiles.buscarVigente(organizationId, personaId).isEmpty()) {
			log.info("Autorizacion rechazada: la persona no es paciente. personaId={}", personaId);
			throw new PersonaSinPerfilPacienteException(personaId);
		}
	}

	private Persona exigirPersonaDelTenant(long organizationId, long personaId) {
		return personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
	}

	/**
	 * La cobertura es de este paciente y esta activa.
	 *
	 * <p>Una cobertura dada de baja no puede respaldar una autorizacion nueva: el financiador ya
	 * no tiene con quien. Las autorizaciones YA cargadas contra ella no se tocan —regla maestra
	 * 10—, que es exactamente el caso borde "cambio de cobertura".
	 */
	private CoberturaPaciente exigirCoberturaOperable(
			long organizationId, long personaId, long coberturaId) {

		CoberturaPaciente cobertura = coberturas
				.findByIdAndOrganizationIdAndPersonaId(coberturaId, organizationId, personaId)
				.orElseThrow(() -> new CoberturaNotAccessibleException(coberturaId));
		if (!cobertura.isActive()) {
			throw new CoberturaInactivaException(coberturaId, "respaldar una autorizacion");
		}
		return cobertura;
	}

	/** La orden, si se declaro, es de este paciente y esta activa. */
	private Long resolverOrden(long organizationId, long personaId, Long ordenMedicaId) {
		if (ordenMedicaId == null) {
			return null;
		}
		OrdenMedica orden = ordenes
				.findByIdAndOrganizationIdAndPersonaId(ordenMedicaId, organizationId, personaId)
				.orElseThrow(() -> new OrdenNotAccessibleException(ordenMedicaId));
		if (!orden.isActive()) {
			throw new OrdenInactivaException(ordenMedicaId, "respaldar una autorizacion");
		}
		return orden.getId();
	}

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

	private Autorizacion cargar(long organizationId, long personaId, long autorizacionId) {
		return autorizaciones
				.findByIdAndOrganizationIdAndPersonaId(autorizacionId, organizationId, personaId)
				.orElseThrow(() -> new AutorizacionNotAccessibleException(autorizacionId));
	}

	private static void exigirOperable(Autorizacion autorizacion, String operacion) {
		if (!autorizacion.isOperable()) {
			log.info("Operacion sobre autorizacion inactiva rechazada: autorizacionId={} "
					+ "operacion={}", autorizacion.getId(), operacion);
			throw new AutorizacionInactivaException(autorizacion.getId(), operacion);
		}
	}

	/**
	 * Guarda con flush.
	 *
	 * <p>El unico unique que puede chocar es {@code uk_autorizacion_numero_vigente}: el mismo
	 * numero cargado dos veces vigente bajo la misma cobertura. Aca la regla <b>si</b> es una
	 * igualdad y el unique la expresa; el solapamiento de vigencias es lo que necesita el lock.
	 */
	private Autorizacion persistir(Autorizacion autorizacion) {
		try {
			return autorizaciones.saveAndFlush(autorizacion);
		} catch (DataIntegrityViolationException choque) {
			log.info("Autorizacion rechazada por unique de numero: personaId={}",
					autorizacion.getPersonaId());
			throw new NumeroDeDocumentoTakenException("autorizacion", autorizacion.getNumero());
		}
	}

	private static long exigirNoNulo(Long valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static <T> T exigirNoNuloRef(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una autorizacion exige un motivo declarado");
		}
		return motivo.strip();
	}

	@SuppressWarnings("java:S107")
	private void auditar(
			String eventType,
			Autorizacion autorizacion,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				autorizacion.getOrganizationId(),
				// Aca SI viaja la sede: a diferencia de la orden y de la cobertura, la
				// autorizacion es de la sede que la gestiono, porque el convenio que la respalda
				// es de la sede (RN-M16-001).
				autorizacion.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_AUTORIZACION,
				autorizacion.getId(),
				previousState,
				newState,
				detalles,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
