package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.ClaveDeBusqueda;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.PersonaDocumentoTakenException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaPosibleDuplicadoException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El padron de personas de una organizacion (M07): buscar, dar de alta y editar.
 *
 * <h2>Lo que esta clase NO hace, y es lo mas importante de su diseño</h2>
 *
 * <p><b>No crea pacientes.</b> Ni historia clinica, ni caso, ni sesion, ni perfil de paciente.
 * Ninguno de sus caminos escribe en {@code perfil_paciente} — el puerto de perfiles esta
 * inyectado <b>solo para leer</b>, y se usa para poder decir en un listado quien es paciente y
 * quien no. Eso es RF-M07-010 y no es una promesa de este javadoc: es estructural, porque activar
 * un perfil vive en {@link PerfilPacienteService} y esta clase no lo conoce.
 *
 * <p>La regla que se protege ahi es la mas facil de romper de todo el modulo. Una inscripcion a
 * una clase de pilates, una venta de un pase, una reserva de un turno de masajes: todos esos
 * caminos futuros van a necesitar dar de alta a una persona, y cualquiera de ellos que ademas le
 * cree un perfil clinico "por las dudas" convierte a un cliente de bienestar en paciente con
 * historia clinica. RN-M07-006 lo prohibe expresamente.
 *
 * <h2>Las dos capas de proteccion contra duplicados, y por que son distintas</h2>
 *
 * <pre>
 *   DOCUMENTO REPETIDO   Invariante DURO. Lo garantiza uk_persona_documento_vigente y se
 *                        responde 409 sin excepcion posible. No hay forma de confirmarlo ni
 *                        de saltearlo: dos personas con el mismo documento en la misma
 *                        organizacion son un error de carga, siempre.
 *   POSIBLE DUPLICADO    ADVERTENCIA. Mismo nombre completo, o mismo telefono. Se responde
 *                        409 con la lista de candidatos y el operador decide: abre la ficha
 *                        que ya existe, o reenvia declarando que es otra persona. Es
 *                        RN-M07-001 —"debe existir busqueda previa a la creacion"— hecho
 *                        cumplir por el BACKEND y no solo por la pantalla.
 * </pre>
 *
 * <p>Que la busqueda previa sea una regla del servidor y no del formulario es deliberado: el
 * backend es la autoridad (regla maestra 12) y un alta por API, por importacion o por una
 * pantalla futura distraida entraria sin ninguna comprobacion. Con la pantalla como unica
 * defensa, la regla dura lo que dura el primer cliente nuevo.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Leer exige {@code paciente:read} (AKINE-DU-6, DP-22) y mutar exige
 * {@code paciente:manage} evaluado con la sede del contexto. Los dos controles viven en
 * {@link AutorizacionDePadron}, y el motivo no obvio de que la evaluacion lleve sede aunque la
 * persona sea de la organizacion esta en {@code PermissionCodes.PACIENTE_MANAGE}.
 */
@Service
public class PersonaService {

	private static final Logger log = LoggerFactory.getLogger(PersonaService.class);

	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final PersonSupportAccessAuditor supportAccessAuditor;

	public PersonaService(
			PersonaRepositoryPort personas,
			PerfilPacienteRepositoryPort perfiles,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			PersonSupportAccessAuditor supportAccessAuditor) {

		this.personas = personas;
		this.perfiles = perfiles;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Busqueda paginada del padron (RF-M07-001).
	 *
	 * <p>Devuelve las personas INACTIVAS cuando se piden explicitamente, y con 200: RN-M07-004
	 * exige que los historicos sigan resolviendo. Con el filtro por defecto ({@code ACTIVO}) el
	 * mostrador nunca ve una ficha dada de baja.
	 *
	 * <p>El perfil de cada persona se resuelve en <b>una sola consulta</b> para toda la pagina y
	 * no fila por fila: preguntarlo por persona son veinte consultas por pantalla, y esa es
	 * exactamente la forma en que una busqueda que anda bien con cien personas se cae con
	 * cincuenta mil.
	 */
	@Transactional(readOnly = true)
	public PersonaPagina buscar(OperatingActor actor, PersonaBusqueda filtros, int page, int size) {
		long organizationId = AutorizacionDePadron.exigirLecturaDelPadron(
				permissionGuard, actor, "Buscar personas");

		List<Persona> encontradas = personas.buscar(
				organizationId,
				filtros.patronNombre(),
				filtros.patronClave(),
				filtros.activoFiltro(),
				filtros.perfilFiltro(),
				page * size,
				size);

		Map<Long, PerfilPaciente> porPersona = perfilesDe(organizationId, encontradas);
		List<PersonaView> contenido = encontradas.stream()
				.map(persona -> PersonaView.de(persona, porPersona.get(persona.getId())))
				.toList();

		long total = personas.contar(
				organizationId,
				filtros.patronNombre(),
				filtros.patronClave(),
				filtros.activoFiltro(),
				filtros.perfilFiltro());

		return new PersonaPagina(contenido, total);
	}

	/** Una persona del tenant, activa o no. 404 si no existe o es de otra organizacion. */
	@Transactional(readOnly = true)
	public PersonaView ver(OperatingActor actor, long personaId) {
		long organizationId = AutorizacionDePadron.exigirLecturaDelPadron(
				permissionGuard, actor, "Ver persona");
		Persona persona = cargar(organizationId, personaId);
		return PersonaView.de(persona, perfiles.buscarVigente(organizationId, personaId).orElse(null));
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de una Persona (RF-M07-002, RF-M07-007).
	 *
	 * <p><b>No crea ningun artefacto clinico</b>, cualquiera sea el motivo del alta: ver la
	 * cabecera de la clase.
	 *
	 * <p>El orden de las comprobaciones importa. Primero la advertencia de coincidencias, que
	 * puede ahorrarse el INSERT entero; despues el INSERT, cuyo choque contra el unique de
	 * documento produce el 409 duro. Al reves —insertar y despues advertir— seria imposible: el
	 * alta ya habria ocurrido.
	 */
	@Transactional
	public PersonaView crear(OperatingActor actor, PersonaAltaCommand command) {
		PermissionDecision decision =
				AutorizacionDePadron.exigirGestionDelPadron(permissionGuard, actor, "Crear persona");
		long organizationId = actor.contextOrganizationId();

		if (!command.confirmaPosibleDuplicado()) {
			exigirSinCoincidencias(organizationId, command);
		}

		Persona persona = new Persona(
				organizationId,
				command.tipoDocumento(),
				command.numeroDocumento(),
				command.apellido(),
				command.nombre(),
				command.fechaNacimiento(),
				command.email(),
				command.telefono(),
				command.notas());

		Persona creada = persistir(persona);

		Instant ahora = Instant.now();
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("conDocumento", String.valueOf(creada.getTipoDocumento() != null));
		detalles.put("confirmoPosibleDuplicado", String.valueOf(command.confirmaPosibleDuplicado()));
		auditar(AuditEvents.PERSONA_CREATED, creada, actor, null, "ACTIVO", null, detalles, ahora);
		registrarSoporte(decision, actor, creada, ahora, "Crear persona");

		log.info("Persona creada: personaId={} organizationId={}", creada.getId(), organizationId);
		return PersonaView.de(creada);
	}

	/**
	 * Edicion parcial de los datos administrativos (RF-M07-003).
	 *
	 * <p>Una persona INACTIVA no se edita: 409. Reabrir una ficha dada de baja para cambiarle el
	 * nombre reescribiria el historico que RN-M07-004 protege.
	 *
	 * <p><b>La deteccion de posibles duplicados no corre en la edicion</b>, y es deliberado.
	 * Corregir el apellido de una ficha hasta que coincida con el de otra persona es un escenario
	 * marginal, mientras que advertir en cada PATCH le pediria confirmacion al operador cada vez
	 * que corrige un telefono. El invariante duro —el documento— si se sigue verificando, porque
	 * lo verifica el unique y no una rama de codigo.
	 */
	@Transactional
	public PersonaView editar(OperatingActor actor, long personaId, PersonaEdicionCommand command) {
		PermissionDecision decision =
				AutorizacionDePadron.exigirGestionDelPadron(permissionGuard, actor, "Editar persona");
		long organizationId = actor.contextOrganizationId();

		Persona persona = cargar(organizationId, personaId);
		exigirOperable(persona, "editar");
		exigirVersion(persona, command.expectedVersion());

		Map<String, String> detalles = cambios(persona, command);

		persona.updateDatos(
				command.tipoDocumento(),
				command.numeroDocumento(),
				command.apellido(),
				command.nombre(),
				command.fechaNacimiento(),
				command.email(),
				command.telefono(),
				command.notas());

		Persona guardada = persistir(persona);

		Instant ahora = Instant.now();
		auditar(AuditEvents.PERSONA_UPDATED, guardada, actor, null, null, null, detalles, ahora);
		registrarSoporte(decision, actor, guardada, ahora, "Editar persona");

		return PersonaView.de(
				guardada, perfiles.buscarVigente(organizationId, personaId).orElse(null));
	}

	/**
	 * Da de baja logica a una persona del padron (RF-M07-005).
	 *
	 * <h2>Lo que la baja hace, y lo que no</h2>
	 *
	 * <p><b>No borra nada.</b> RN-M07-004 lo prohibe expresamente y la etapa lo repite: "baja no
	 * borra historial". La ficha se sigue leyendo con 200, sus turnos siguen existiendo, sus
	 * obligaciones siguen debiendose y sus adjuntos se siguen descargando. Lo que deja de admitir
	 * son operaciones NUEVAS: editarla, activarle un perfil, adjuntarle documentos.
	 *
	 * <p><b>Da de baja tambien el perfil de paciente vigente, en la misma transaccion.</b> No es
	 * una comodidad: dejar vivo el perfil de una persona dada de baja produciria una ficha que
	 * {@code PacienteDirectory} sigue reportando como paciente vigente, y los modulos rio abajo
	 * —que preguntan por {@code esPacienteVigente}— dejarian reservar turnos a alguien que el
	 * padron considera cerrado. El caso inverso —dar de baja el perfil sin dar de baja a la
	 * persona— si es una operacion propia, y vive en {@code PerfilPacienteService.desactivar}.
	 *
	 * <p><b>Libera el documento.</b> El unique de {@code V27} lleva {@code deleted_key}, asi que en
	 * cuanto la ficha se da de baja su documento vuelve a estar disponible para un alta nueva. Es
	 * intencional y es lo que permite corregir una ficha creada mal sin borrarla.
	 *
	 * <p><b>El motivo lo exige el dominio</b>, no un {@code @NotBlank} del DTO: una validacion que
	 * solo vive en la capa web no protege a los llamadores que no son la capa web.
	 *
	 * <p><b>Lo que esta baja NO valida, y hay que saberlo:</b> no comprueba si la persona tiene
	 * turnos futuros ni deuda abierta. Es deliberado y es la diferencia con la baja de un
	 * consultorio o de un espacio, que si tienen sonda de referencias activas: dar de baja a una
	 * persona con deuda es un caso legitimo y frecuente —se fue del centro y sigue debiendo— y
	 * bloquearlo obligaria a condonar para poder cerrar la ficha. Un aviso previo en la pantalla es
	 * la respuesta correcta a eso, no un 409.
	 */
	@Transactional
	public PersonaView darDeBaja(
			OperatingActor actor, long personaId, String motivo, long expectedVersion) {

		PermissionDecision decision = AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Dar de baja una persona");
		long organizationId = actor.contextOrganizationId();

		Persona persona = cargar(organizationId, personaId);
		exigirOperable(persona, "una nueva baja");
		exigirVersion(persona, expectedVersion);

		Instant ahora = Instant.now();
		persona.deactivate(ahora, motivo);
		Persona guardada = personas.saveAndFlush(persona);

		Map<String, String> detalles = new LinkedHashMap<>();
		perfiles.buscarVigente(organizationId, personaId).ifPresent(perfil -> {
			perfil.deactivate(ahora, motivo);
			perfiles.save(perfil);
			detalles.put("perfilPacienteDadoDeBaja", String.valueOf(perfil.getId()));
		});

		auditar(AuditEvents.PERSONA_DEACTIVATED, guardada, actor,
				"ACTIVO", "INACTIVO", motivo, detalles, ahora);
		registrarSoporte(decision, actor, guardada, ahora, "Dar de baja una persona");

		log.info("Persona dada de baja: personaId={} organizationId={} perfilTambien={}",
				personaId, organizationId, !detalles.isEmpty());
		return PersonaView.de(guardada);
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * Carga una persona del tenant o lanza 404.
	 *
	 * <p>Privada, y {@link PerfilPacienteService} tiene su propia copia de tres lineas en vez de
	 * llamar a esta. Es el ruling R1 de 02.06: un servicio de aplicacion que llama a otro arrastra
	 * su autorizacion, su transaccion y su auditoria a un camino que no las pidio. Los dos leen el
	 * mismo puerto con el mismo filtro por tenant, que es lo unico que tiene que coincidir.
	 */
	private Persona cargar(long organizationId, long personaId) {
		return personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
	}

	/** Una persona dada de baja no admite operaciones nuevas: 409, nunca 404. */
	private static void exigirOperable(Persona persona, String operacion) {
		if (!persona.isOperable()) {
			throw new PersonaInactivaException(persona.getId(), operacion);
		}
	}

	private static void exigirVersion(Persona persona, long esperada) {
		if (persona.getVersion() != esperada) {
			// OptimisticLockingFailureException PLANO, no la subclase de JPA: el handler global lo
			// mapea a 409 con type = conflict. concurrent-modification lo emite solo el advice de
			// organization, y prometerlo aca repetiria la inexactitud que arrastran los contratos
			// publicados de 02.02 y 02.05.
			throw new OptimisticLockingFailureException(
					"La persona fue modificada por otra operacion");
		}
	}

	/**
	 * Rechaza el alta si hay coincidencias y nadie las confirmo (RN-M07-001).
	 *
	 * <p>Solo coincidencias EXACTAS sobre claves normalizadas — ver
	 * {@code PersonaPosibleDuplicadoException} para por que un detector generoso protege menos que
	 * uno estricto.
	 */
	private void exigirSinCoincidencias(long organizationId, PersonaAltaCommand command) {
		List<Long> candidatos = personas.buscarCoincidencias(
						organizationId,
						ClaveDeBusqueda.deNombre(command.apellido()),
						ClaveDeBusqueda.deNombre(command.nombre()),
						ClaveDeBusqueda.deTelefono(command.telefono()))
				.stream()
				.map(Persona::getId)
				.toList();

		if (!candidatos.isEmpty()) {
			log.info("Alta de persona detenida por posibles duplicados: organizationId={} candidatos={}",
					organizationId, candidatos.size());
			throw new PersonaPosibleDuplicadoException(candidatos);
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/**
	 * Guarda forzando el flush y traduce el choque del unique de documento.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA</b>: ni una lectura
	 * para averiguar quien tiene ese documento, ni un {@code save}, ni la auditoria. La
	 * especificacion lo prohibe y lo que sale de ahi es un 500 en vez del 409 legitimo. Por eso el
	 * id de la persona que ya tiene el documento <b>no se busca aca</b> y la excepcion viaja con
	 * {@code null}: quien quiera resolverlo tiene que hacerlo en otra transaccion, y esa
	 * comodidad no justifica arriesgar el 409. Mismo patron, y misma trampa, que
	 * {@code ServicioService.persistir} y {@code CatalogoService.persistir}.
	 */
	private Persona persistir(Persona persona) {
		try {
			return personas.saveAndFlush(persona);
		} catch (DataIntegrityViolationException choque) {
			log.info("Alta o edicion de persona rechazada por documento repetido: organizationId={}",
					persona.getOrganizationId());
			throw new PersonaDocumentoTakenException(null);
		}
	}

	/**
	 * Los perfiles vigentes de una pagina de personas, indexados por persona.
	 *
	 * <p>Corta antes de consultar cuando la pagina viene vacia: {@code IN ()} es sintaxis invalida
	 * en MySQL y una pagina vacia es el caso normal de una busqueda sin resultados.
	 */
	private Map<Long, PerfilPaciente> perfilesDe(long organizationId, List<Persona> encontradas) {
		if (encontradas.isEmpty()) {
			return Map.of();
		}
		List<Long> ids = encontradas.stream().map(Persona::getId).toList();
		return perfiles.buscarVigentesDePersonas(organizationId, ids).stream()
				.collect(java.util.stream.Collectors.toMap(PerfilPaciente::getPersonaId, p -> p));
	}

	/**
	 * Los cambios efectivos, para el detalle de la auditoria.
	 *
	 * <p><b>Registra QUE campo cambio, nunca A QUE valor.</b> Es la diferencia con
	 * {@code ServicioService.cambios}, que si escribe "viejo -> nuevo": alli los valores son
	 * nombres de un catalogo publico y aca son PII. Una fila de auditoria que reproduce el
	 * documento o el telefono se los entrega a quien tiene {@code auditoria:read} y no
	 * {@code paciente:manage}, que es exactamente la separacion que RNF-M07-001 pide sostener.
	 */
	private static Map<String, String> cambios(Persona persona, PersonaEdicionCommand command) {
		Map<String, String> detalles = new LinkedHashMap<>();
		anotarSiCambia(detalles, "apellido", persona.getApellido(), command.apellido());
		anotarSiCambia(detalles, "nombre", persona.getNombre(), command.nombre());
		anotarSiCambia(detalles, "email", persona.getEmail(), command.email());
		anotarSiCambia(detalles, "telefono", persona.getTelefono(), command.telefono());
		anotarSiCambia(detalles, "notas", persona.getNotas(), command.notas());
		if (command.fechaNacimiento() != null
				&& !command.fechaNacimiento().equals(persona.getFechaNacimiento())) {
			detalles.put("fechaNacimiento", "modificada");
		}
		if (documentoCambia(persona, command.tipoDocumento(), command.numeroDocumento())) {
			detalles.put("documento", "modificado");
		}
		return detalles;
	}

	private static void anotarSiCambia(
			Map<String, String> detalles, String campo, String actual, String pedido) {
		if (pedido != null && !pedido.strip().equals(actual)) {
			detalles.put(campo, "modificado");
		}
	}

	private static boolean documentoCambia(Persona persona, TipoDocumento tipo, String numero) {
		if (tipo == null) {
			return false;
		}
		return tipo != persona.getTipoDocumento()
				|| !java.util.Objects.equals(
						ClaveDeBusqueda.deDocumento(numero), persona.getDocumentoClave());
	}

	private void auditar(
			String eventType,
			Persona persona,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				persona.getOrganizationId(),
				// consultorioId NULL: una Persona no pertenece a ninguna sede. La sede del contexto
				// autorizo la operacion pero no es el alcance del hecho, y escribirla aca haria
				// creer que la persona "es de esa sede", que es justo lo que V27 decidio que no.
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_PERSONA,
				persona.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	/**
	 * Deja la fila {@code SUPPORT_ACCESS_USED} cuando el permiso se concedio por soporte.
	 *
	 * <p>La escribe {@link PersonSupportAccessAuditor} en una transaccion propia, para que sobreviva a
	 * un rollback posterior del negocio. El invariante de la matriz seccion 7 —el acceso de
	 * plataforma a datos de un tenant es justificado <b>y</b> auditado— no se cumple a medias.
	 */
	private void registrarSoporte(
			PermissionDecision decision,
			OperatingActor actor,
			Persona persona,
			Instant ahora,
			String operacion) {

		if (!decision.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				persona.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_PERSONA,
				persona.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				null,
				AuditEvents.correlationId(),
				ahora));
	}
}
