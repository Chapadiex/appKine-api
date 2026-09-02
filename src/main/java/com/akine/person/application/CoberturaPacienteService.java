package com.akine.person.application;

import com.akine.contracting.spi.CoberturaCatalogoDirectory;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.exception.CoberturaInactivaException;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.CoberturaPrincipalSuperpuestaException;
import com.akine.person.domain.exception.CoberturaSuperpuestaException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.exception.PlanNoSeleccionableException;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPersonaLockRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * Las coberturas de un paciente (M08, AKINE-03.04).
 *
 * <h2>1. Este servicio COPIA del catalogo, no lo consulta al mostrar</h2>
 *
 * <p>{@code contracting.spi} separa a proposito dos cosas y esta clase es su primer consumidor:
 *
 * <pre>
 *   planesSeleccionables / find*   LECTURA VIVA, para DECIDIR. La usa la pantalla que ofrece
 *                                  planes, no este servicio.
 *   congelar(...)                  COPIA, para GUARDAR. Es lo unico que esta clase llama, y
 *                                  solo en el ALTA.
 * </pre>
 *
 * <p>Despues del alta, <b>ninguna lectura de cobertura vuelve a tocar {@code contracting}</b>. Ni
 * el listado, ni la seleccion para una atencion, ni la edicion. Si lo hicieran, renombrar un plan
 * reescribiria retroactivamente coberturas ya firmadas y bajarle el copago cambiaria liquidaciones
 * ya presentadas. Es el mismo patron con que {@code obligacion} congela el precio de la oferta en
 * 07.01, y esta ausencia de llamadas es la garantia: no hay ningun campo del catalogo que este
 * servicio pueda leer tarde porque no lo pide nunca.
 *
 * <h2>2. Las dos reglas temporales las hace cumplir un LOCK, nunca un indice</h2>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints y ningun UNIQUE puede decir que dos intervalos se
 * pisan. Las dos invariantes entre filas —el mismo plan solapado, y la segunda principal
 * solapada— se verifican bajo el lock de {@code cobertura_persona_lock}, y las mutaciones que las
 * tocan van en <b>{@code READ_COMMITTED}</b>: con {@code REPEATABLE READ} InnoDB fija la foto en
 * la primera lectura consistente, que ocurre antes del lock, y las dos transacciones concurrentes
 * pasarian la comprobacion. Es la leccion que 05.02 pago con {@code agenda_sede}.
 *
 * <p>La fila-lock se crea en una transaccion aparte —{@link CoberturaLockIniciador}— y nunca
 * perezosamente dentro de la que la bloquea.
 *
 * <h2>3. Lo que NO es regla, y conviene no "arreglar"</h2>
 *
 * <p>Dos coberturas de financiadores <b>distintos</b> solapadas son legitimas: obra social y
 * prepaga a la vez es el caso normal. Y una credencial vencida <b>no invalida</b> la cobertura: se
 * informa como alerta, porque vencerla automaticamente daria de baja coberturas reales por un dato
 * que el mostrador copia a mano.
 *
 * <h2>4. Cobertura del paciente NO es convenio del consultorio (RN-M08-004)</h2>
 *
 * <p>Nada de lo que esta clase devuelve afirma que la prestacion sea facturable a ese financiador.
 * Resolver la elegibilidad por oferta y convenio es RF-M08-006, necesita M16 y M17, y no se
 * adelanta: adelantarlo seria construir un consumidor antes que su cimiento, que es el primero de
 * los tres errores estructurales que AGENT.md §9 nombra.
 */
@Service
public class CoberturaPacienteService {

	private static final Logger log = LoggerFactory.getLogger(CoberturaPacienteService.class);

	private final CoberturaPacienteRepositoryPort coberturas;
	private final CoberturaPersonaLockRepositoryPort candados;
	private final CoberturaLockIniciador iniciador;
	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;
	private final CoberturaCatalogoDirectory catalogo;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public CoberturaPacienteService(
			CoberturaPacienteRepositoryPort coberturas,
			CoberturaPersonaLockRepositoryPort candados,
			CoberturaLockIniciador iniciador,
			PersonaRepositoryPort personas,
			PerfilPacienteRepositoryPort perfiles,
			CoberturaCatalogoDirectory catalogo,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.coberturas = coberturas;
		this.candados = candados;
		this.iniciador = iniciador;
		this.personas = personas;
		this.perfiles = perfiles;
		this.catalogo = catalogo;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * El historial completo de coberturas del paciente, mas nuevas primero.
	 *
	 * <p>Trae tambien las dadas de baja y las vencidas: RN-M08-003 y la regla maestra 10. Sin
	 * ellas no se puede explicar con que cobertura se atendio al paciente el mes pasado, que es
	 * exactamente lo que el criterio de aceptacion de la etapa pide preservar.
	 *
	 * <p>Se autoriza por <b>pertenencia</b> y no con {@code paciente:manage}, mismo criterio que
	 * las lecturas del padron en 03.01: un profesional que va a atender necesita saber con que
	 * cobertura viene el paciente, y exigirle el permiso de gestion lo dejaria afuera. El hueco que
	 * eso deja abierto es el mismo que declara 03.01 —una membership con rol PACIENTE lee mas de lo
	 * que deberia— y su causa de fondo tambien: el alcance {@code OWN} no esta implementado.
	 */
	@Transactional(readOnly = true)
	public List<CoberturaView> listar(
			OperatingActor actor, long personaId, CoberturaEstadoFiltro estado, LocalDate fecha) {

		long organizationId =
				AutorizacionDePadron.exigirContexto(actor, "Listar coberturas del paciente");
		exigirPersonaDelTenant(organizationId, personaId);

		CoberturaEstadoFiltro filtro = estado == null ? CoberturaEstadoFiltro.TODAS : estado;
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		return coberturas.historial(organizationId, personaId).stream()
				.filter(cobertura -> switch (filtro) {
					case ACTIVA -> cobertura.isActive();
					case INACTIVA -> !cobertura.isActive();
					case TODAS -> true;
				})
				.map(cobertura -> CoberturaView.de(cobertura, contra))
				.toList();
	}

	/**
	 * Que se puede elegir para atender a este paciente ese dia (RF-M08-004, RF-M08-005).
	 *
	 * <p>Es una <b>lectura</b> y no persiste nada: quien registra el hecho copia lo elegido a sus
	 * propias columnas. Ver {@link SeleccionDeCobertura}, que explica por que Particular viaja como
	 * un campo y no como una fila.
	 */
	@Transactional(readOnly = true)
	public SeleccionDeCobertura resolverParaAtencion(
			OperatingActor actor, long personaId, LocalDate fecha) {

		long organizationId =
				AutorizacionDePadron.exigirContexto(actor, "Resolver la cobertura de la atencion");
		exigirPersonaDelTenant(organizationId, personaId);

		LocalDate dia = fecha == null ? LocalDate.now() : fecha;

		List<CoberturaView> vigentes = coberturas.activasDe(organizationId, personaId).stream()
				.filter(cobertura -> cobertura.vigenteEl(dia))
				.map(cobertura -> CoberturaView.de(cobertura, dia))
				.toList();

		// Determinista sin desempate: el invariante del lock impide que haya dos principales
		// vigentes el mismo dia, asi que este findFirst no esta eligiendo entre candidatas.
		CoberturaView principal = vigentes.stream()
				.filter(CoberturaView::principal)
				.findFirst()
				.orElse(null);

		return SeleccionDeCobertura.de(dia, principal, vigentes);
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Agrega una cobertura al paciente (RF-M08-001).
	 *
	 * <p>El plan se congela contra {@code vigenciaDesde} y no contra hoy: la pregunta que hay que
	 * contestar es si ese plan se podia elegir el dia en que la cobertura empieza a valer. Cargar
	 * hoy una cobertura que arranca el mes que viene con un plan que vence la semana proxima seria,
	 * si no, un alta valida que nace muerta.
	 *
	 * <p>{@code congelar} devuelve vacio por cinco causas distintas —el plan no existe, es ajeno,
	 * esta dado de baja, su financiador esta dado de baja, o la fecha cae fuera de su vigencia— y
	 * las cinco se responden igual: ver {@link PlanNoSeleccionableException}.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CoberturaView agregar(
			OperatingActor actor, long personaId, CoberturaAltaCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Agregar una cobertura");
		long organizationId = actor.contextOrganizationId();

		exigirPacienteVigente(organizationId, personaId, "cargar una cobertura");

		LocalDate desde = exigirNoNulo(
				command.vigenciaDesde(), "La vigencia de la cobertura necesita una fecha de inicio");
		boolean principal = Boolean.TRUE.equals(command.principal());

		// PASO 0. En su PROPIA transaccion: crearla dentro de esta deadlockea entre las primeras
		// coberturas concurrentes de una persona. Ver CoberturaLockIniciador.
		iniciador.asegurar(organizationId, personaId);

		// PASO 1. Antes de leer nada de coberturas. Ver la cabecera de la clase.
		BloqueoDeCoberturas.tomar(candados, organizationId, personaId);

		CoberturaPaciente cobertura = command.tipo() == TipoCobertura.PARTICULAR
				? CoberturaPaciente.particular(
						organizationId, personaId, desde, command.vigenciaHasta(), principal,
						command.observaciones())
				: CoberturaPaciente.financiada(
						organizationId,
						personaId,
						congelar(organizationId, command.planId(), desde),
						command.numeroAfiliado(),
						command.credencialVigenciaHasta(),
						desde,
						command.vigenciaHasta(),
						principal,
						command.observaciones());

		// PASO 2. Bajo el lock. Ningun unique puede expresar estas dos reglas.
		exigirSinSolapamiento(
				organizationId, personaId, null, cobertura.getPlanId(), desde,
				command.vigenciaHasta());
		if (principal) {
			exigirSinPrincipalSolapada(
					organizationId, personaId, null, desde, command.vigenciaHasta());
		}

		CoberturaPaciente creada = persistir(cobertura);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("tipo", creada.getTipo().name());
		detalles.put("vigenciaDesde", String.valueOf(creada.getVigenciaDesde()));
		if (creada.getPlanId() != null) {
			detalles.put("planId", String.valueOf(creada.getPlanId()));
			detalles.put("planCodigo", creada.getPlanCodigo());
		}
		// El numero de afiliado NO va a la auditoria: es PII del paciente ante su financiador y la
		// auditoria se consulta con permisos distintos a los del padron. Ver AuditEvents.
		auditar(AuditEvents.COBERTURA_CREATED, creada, actor, null, "ACTIVA", null, detalles);

		log.info("Cobertura creada: coberturaId={} personaId={} tipo={}",
				creada.getId(), personaId, creada.getTipo());
		return CoberturaView.de(creada, LocalDate.now());
	}

	/**
	 * Edita los datos NO historicos y, si viene {@code vigenciaHasta}, cierra la vigencia
	 * (RF-M08-002 y RF-M08-003).
	 *
	 * <p><b>El plan no se puede cambiar</b>, y no por una validacion: los nueve campos de la copia
	 * congelada son {@code updatable = false} en la entidad y el comando ni siquiera los ofrece.
	 * Cambiar de plan es finalizar esta cobertura y agregar otra, porque editarla en el lugar
	 * reescribiria con que cobertura se atendio al paciente el mes pasado.
	 *
	 * <p>Una cobertura dada de baja no se edita: 409. Reabrir la ficha de algo dado de baja
	 * reescribiria el historico que RN-M08-003 protege.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CoberturaView editar(
			OperatingActor actor,
			long personaId,
			long coberturaId,
			CoberturaEdicionCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(permissionGuard, actor, "Editar una cobertura");
		long organizationId = actor.contextOrganizationId();

		exigirPacienteVigente(organizationId, personaId, "editar una cobertura");

		iniciador.asegurar(organizationId, personaId);
		BloqueoDeCoberturas.tomar(candados, organizationId, personaId);

		CoberturaPaciente cobertura = cargar(organizationId, personaId, coberturaId);
		exigirOperable(cobertura, "editar");
		exigirVersion(cobertura, command.expectedVersion());

		Map<String, String> detalles = cambios(cobertura, command);
		boolean cierraVigencia = command.vigenciaHasta() != null
				&& !command.vigenciaHasta().equals(cobertura.getVigenciaHasta());

		cobertura.updateDatos(
				command.numeroAfiliado(),
				command.credencialVigenciaHasta(),
				command.vigenciaDesde(),
				command.observaciones());
		if (command.vigenciaHasta() != null) {
			cobertura.finalizarVigencia(command.vigenciaHasta());
		}

		// Mover fechas puede crear un solapamiento que el alta no tenia. Se revalida bajo el
		// mismo lock, excluyendo la fila que se esta editando.
		exigirSinSolapamiento(
				organizationId, personaId, cobertura.getId(), cobertura.getPlanId(),
				cobertura.getVigenciaDesde(), cobertura.getVigenciaHasta());
		if (cobertura.isPrincipal()) {
			exigirSinPrincipalSolapada(
					organizationId, personaId, cobertura.getId(),
					cobertura.getVigenciaDesde(), cobertura.getVigenciaHasta());
		}

		CoberturaPaciente guardada = persistir(cobertura);

		auditar(
				cierraVigencia
						? AuditEvents.COBERTURA_VIGENCIA_FINALIZADA
						: AuditEvents.COBERTURA_UPDATED,
				guardada, actor, null, null, null, detalles);

		return CoberturaView.de(guardada, LocalDate.now());
	}

	/**
	 * Marca o desmarca la cobertura principal del paciente (RF-M08-004).
	 *
	 * <p>Operacion propia y no un campo del PUT: es un invariante <b>entre filas</b> y no un dato
	 * de esta. Meterlo en la edicion obligaria a tomar el lock en toda edicion aunque no cambie la
	 * marca, y a que el cliente mande la version de una fila para modificar el estado de otra.
	 *
	 * <p><b>No desmarca automaticamente a la anterior.</b> Si ya hay una principal vigente en el
	 * periodo, responde 409 con su id. Desmarcarla en silencio significaria que un click cambia dos
	 * coberturas, y la que se cambia sin pedirlo es la que despues nadie puede explicar.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CoberturaView marcarPrincipal(
			OperatingActor actor, long personaId, long coberturaId, boolean principal) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Elegir la cobertura principal");
		long organizationId = actor.contextOrganizationId();

		exigirPacienteVigente(organizationId, personaId, "elegir su cobertura principal");

		iniciador.asegurar(organizationId, personaId);
		BloqueoDeCoberturas.tomar(candados, organizationId, personaId);

		CoberturaPaciente cobertura = cargar(organizationId, personaId, coberturaId);
		exigirOperable(cobertura, "ser marcada principal");

		if (principal) {
			exigirSinPrincipalSolapada(
					organizationId, personaId, cobertura.getId(),
					cobertura.getVigenciaDesde(), cobertura.getVigenciaHasta());
		}

		String anterior = cobertura.isPrincipal() ? "PRINCIPAL" : "SECUNDARIA";
		cobertura.marcarPrincipal(principal);
		CoberturaPaciente guardada = coberturas.save(cobertura);

		auditar(AuditEvents.COBERTURA_PRINCIPAL_CHANGED, guardada, actor,
				anterior, principal ? "PRINCIPAL" : "SECUNDARIA", null, Map.of());

		log.info("Cobertura principal cambiada: coberturaId={} personaId={} principal={}",
				coberturaId, personaId, principal);
		return CoberturaView.de(guardada, LocalDate.now());
	}

	/**
	 * Baja logica con motivo obligatorio.
	 *
	 * <p><b>NO es lo mismo que finalizar la vigencia.</b> Finalizar es "el paciente cambio de obra
	 * social": la cobertura queda ACTIVA y sigue explicando el pasado. Dar de baja es "esta
	 * cobertura nunca debio cargarse". Ninguna de las dos borra nada ni toca un hecho ya
	 * registrado (RN-M08-003, regla maestra 10).
	 *
	 * <p>La baja tambien desmarca la principal: dejar como preferida una cobertura dada de baja
	 * haria que la seleccion del dia siguiente apuntara a algo que ya no existe operativamente.
	 */
	@Transactional
	public CoberturaView darDeBaja(
			OperatingActor actor, long personaId, long coberturaId, String motivo) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Dar de baja una cobertura");
		long organizationId = actor.contextOrganizationId();

		// A diferencia de las otras tres mutaciones, la baja NO toma el lock: quitar una fila no
		// puede crear un solapamiento ni una segunda principal. Serializarla igual solo agregaria
		// contencion sin proteger ninguna invariante.
		exigirPersonaDelTenant(organizationId, personaId);

		CoberturaPaciente cobertura = cargar(organizationId, personaId, coberturaId);
		exigirOperable(cobertura, "dar de baja");

		cobertura.deactivate(Instant.now(), exigirMotivo(motivo));
		CoberturaPaciente guardada = coberturas.save(cobertura);

		auditar(AuditEvents.COBERTURA_DEACTIVATED, guardada, actor,
				"ACTIVA", "INACTIVA", motivo, Map.of());

		log.info("Cobertura dada de baja: coberturaId={} personaId={}", coberturaId, personaId);
		return CoberturaView.de(guardada, LocalDate.now());
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * La copia congelada del plan. <b>La unica llamada al catalogo de todo este servicio.</b>
	 *
	 * <p>Lo devuelto ya no depende de {@code contracting}: es una copia que la entidad escribe en
	 * sus propias columnas y nadie vuelve a pedir.
	 */
	private ReferenciaDeCobertura congelar(long organizationId, Long planId, LocalDate fecha) {
		long plan = exigirNoNulo(planId, "Una cobertura financiada exige un plan");
		return catalogo.congelar(organizationId, plan, fecha)
				.orElseThrow(() -> {
					log.info("Alta de cobertura rechazada: plan no seleccionable. planId={} fecha={}",
							plan, fecha);
					return new PlanNoSeleccionableException(plan);
				});
	}

	/**
	 * No hay otra cobertura ACTIVA del MISMO plan con la vigencia solapada.
	 *
	 * <p>Se salta las PARTICULAR ({@code planId} nulo): "particular de enero a marzo" y "particular
	 * de febrero a abril" no son un duplicado de nada, porque no hay ninguna afiliacion que se
	 * pueda repetir. RN-M08-001 exige que Particular este siempre disponible y bloquearla contra si
	 * misma iria en contra.
	 */
	private void exigirSinSolapamiento(
			long organizationId,
			long personaId,
			Long excluirId,
			Long planId,
			LocalDate desde,
			LocalDate hasta) {

		if (planId == null) {
			return;
		}
		coberturas.activasDe(organizationId, personaId).stream()
				.filter(otra -> !otra.getId().equals(excluirId))
				.filter(otra -> planId.equals(otra.getPlanId()))
				.filter(otra -> otra.seSolapaCon(desde, hasta))
				.findFirst()
				.ifPresent(otra -> {
					log.info("Cobertura rechazada por solapamiento: personaId={} planId={} contra={}",
							personaId, planId, otra.getId());
					throw new CoberturaSuperpuestaException(otra.getId());
				});
	}

	/** No hay otra cobertura ACTIVA marcada principal con la vigencia solapada. */
	private void exigirSinPrincipalSolapada(
			long organizationId,
			long personaId,
			Long excluirId,
			LocalDate desde,
			LocalDate hasta) {

		coberturas.activasDe(organizationId, personaId).stream()
				.filter(otra -> !otra.getId().equals(excluirId))
				.filter(CoberturaPaciente::isPrincipal)
				.filter(otra -> otra.seSolapaCon(desde, hasta))
				.findFirst()
				.ifPresent(otra -> {
					log.info("Principal rechazada por solapamiento: personaId={} contra={}",
							personaId, otra.getId());
					throw new CoberturaPrincipalSuperpuestaException(otra.getId());
				});
	}

	/**
	 * La persona existe en el tenant, esta vigente y <b>es paciente</b>.
	 *
	 * <p>Las tres condiciones responden distinto a proposito: 404 si no es de este tenant —jamas
	 * 403, que confirmaria que existe—, 409 si esta dada de baja, y 409 propio si no tiene perfil
	 * de paciente. Ese ultimo es RF-M07-010 sostenido desde M08: una Persona no es un Paciente, y
	 * cargarle la obra social a un contacto administrativo no significa nada.
	 */
	private void exigirPacienteVigente(long organizationId, long personaId, String operacion) {
		Persona persona = exigirPersonaDelTenant(organizationId, personaId);
		if (!persona.isActive()) {
			throw new PersonaInactivaException(personaId, operacion);
		}
		if (perfiles.buscarVigente(organizationId, personaId).isEmpty()) {
			log.info("Cobertura rechazada: la persona no es paciente. personaId={}", personaId);
			throw new PersonaSinPerfilPacienteException(personaId);
		}
	}

	private Persona exigirPersonaDelTenant(long organizationId, long personaId) {
		return personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
	}

	/**
	 * La cobertura de ESA persona y ESE tenant.
	 *
	 * <p>Las tres columnas del filtro son necesarias: sin {@code organizationId} una cobertura
	 * ajena resolveria, y sin {@code personaId} la cobertura de otro paciente respondería a una
	 * ruta que no le corresponde — una respuesta que miente sobre de quien es.
	 */
	private CoberturaPaciente cargar(long organizationId, long personaId, long coberturaId) {
		return coberturas
				.findByIdAndOrganizationIdAndPersonaId(coberturaId, organizationId, personaId)
				.orElseThrow(() -> new CoberturaNotAccessibleException(coberturaId));
	}

	private static void exigirOperable(CoberturaPaciente cobertura, String operacion) {
		if (!cobertura.isOperable()) {
			log.info("Operacion sobre cobertura inactiva rechazada: coberturaId={} operacion={}",
					cobertura.getId(), operacion);
			throw new CoberturaInactivaException(cobertura.getId(), operacion);
		}
	}

	private static void exigirVersion(CoberturaPaciente cobertura, long esperada) {
		if (cobertura.getVersion() != esperada) {
			throw new OptimisticLockingFailureException(
					"La cobertura fue modificada por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/**
	 * Guarda con flush.
	 *
	 * <p>El unico unique que puede chocar es {@code uk_cobertura_afiliado_vigente}: el mismo numero
	 * de afiliado cargado dos veces vigente bajo el mismo plan. Se traduce a
	 * {@link CoberturaSuperpuestaException} sin id de contraparte porque la fila que choca puede ser
	 * de OTRO paciente —dos personas con el mismo carnet es un error de carga— y devolver su id
	 * filtraria a que otro paciente pertenece.
	 */
	private CoberturaPaciente persistir(CoberturaPaciente cobertura) {
		try {
			return coberturas.saveAndFlush(cobertura);
		} catch (DataIntegrityViolationException choque) {
			log.info("Cobertura rechazada por unique de afiliado: personaId={}",
					cobertura.getPersonaId());
			throw new CoberturaSuperpuestaException(
					cobertura.getId() == null ? 0L : cobertura.getId());
		}
	}

	/** Los cambios efectivos, para el detalle de la auditoria. Nunca el numero de afiliado. */
	private static Map<String, String> cambios(
			CoberturaPaciente cobertura, CoberturaEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (command.numeroAfiliado() != null) {
			// Se registra QUE cambio, no A QUE valor: es PII. Ver AuditEvents.
			detalles.put("numeroAfiliado", "actualizado");
		}
		if (command.credencialVigenciaHasta() != null
				&& !command.credencialVigenciaHasta().equals(cobertura.getCredencialVigenciaHasta())) {
			detalles.put("credencialVigenciaHasta",
					cobertura.getCredencialVigenciaHasta() + " -> "
							+ command.credencialVigenciaHasta());
		}
		if (command.vigenciaDesde() != null
				&& !command.vigenciaDesde().equals(cobertura.getVigenciaDesde())) {
			detalles.put("vigenciaDesde",
					cobertura.getVigenciaDesde() + " -> " + command.vigenciaDesde());
		}
		if (command.vigenciaHasta() != null
				&& !command.vigenciaHasta().equals(cobertura.getVigenciaHasta())) {
			detalles.put("vigenciaHasta",
					cobertura.getVigenciaHasta() + " -> " + command.vigenciaHasta());
		}
		return detalles;
	}

	private void auditar(
			String eventType,
			CoberturaPaciente cobertura,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				cobertura.getOrganizationId(),
				// consultorioId NULL, mismo criterio que PersonaService: la cobertura es de la
				// persona, que pertenece a la ORGANIZACION y no a la sede. La sede del contexto
				// autorizo la operacion pero no es el alcance del hecho.
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_COBERTURA,
				cobertura.getId(),
				previousState,
				newState,
				detalles,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		return Optional.ofNullable(valor)
				.orElseThrow(() -> new IllegalArgumentException(mensaje));
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("La baja de una cobertura exige un motivo declarado");
		}
		return motivo.strip();
	}
}
