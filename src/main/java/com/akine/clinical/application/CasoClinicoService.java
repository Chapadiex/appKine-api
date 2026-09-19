package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.CasoEvento;
import com.akine.clinical.domain.CasoProfesional;
import com.akine.clinical.domain.EstadoCaso;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.TipoEventoCaso;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.CasoClinicoPosibleDuplicadoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.OfertaNoVigenteException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoEventoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoNumeradorPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoProfesionalRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El Caso Clinico: apertura, edicion, cierre, reapertura y equipo (M10, RF-M10-001..006).
 *
 * <h2>Historia Clinica != Caso != Sesion</h2>
 *
 * <p>Es la regla maestra 1 y este servicio es donde se hace cumplir. El caso cuelga de la
 * historia, que sigue existiendo sin casos; agrupa sesiones, que son de {@code encounter} y que
 * este servicio no toca; y <b>no guarda contador de sesiones</b>, porque un contador cacheado se
 * desincroniza y un numerador no puede.
 *
 * <h2>El correlativo, y el deadlock que no se paga una quinta vez</h2>
 *
 * <p>El numero del caso sale de {@code caso_numerador} con {@code UPDATE ultimo_numero + 1},
 * nunca de un {@code MAX + 1}: dos administrativos abriendo un caso para el mismo paciente al
 * mismo tiempo desde dos sedes se llevarian el mismo numero. La fila del numerador se asegura en
 * una <b>transaccion aparte</b> —{@link CasoNumeradorIniciador}— porque crearla dentro de la que
 * despues la bloquea produce deadlock, y atrapar la excepcion no salva.
 *
 * <p>Y el alta corre en {@code READ_COMMITTED} y no en el {@code REPEATABLE READ} por defecto:
 * InnoDB fija la foto en la primera lectura consistente, que ocurre <b>antes</b> del lock, asi que
 * bajo {@code REPEATABLE READ} el {@code SELECT} posterior al incremento podria leer el valor
 * viejo. Es la regla que 05.02 dejo fijada para toda mutacion que serializa.
 *
 * <h2>Duplicado razonable, no duplicado prohibido</h2>
 *
 * <p>RN-M10-002 admite <b>varios casos activos</b>: una rodilla y un hombro son dos casos
 * legitimos el mismo dia, y por eso no hay ningun unique que lo impida. Un alta que coincide en
 * oferta con un caso activo se detiene con 409 y la lista de candidatos, y se confirma reenviando
 * — el mismo mecanismo del alta de Persona (RN-M07-001), que los usuarios ya conocen. La ventana
 * de carrera existe y <b>no se pretende cerrarla</b>: el resultado correcto no es rechazar, es que
 * los dos entren y alguien los unifique despues.
 *
 * <h2>Todo acceso se audita, incluida la lectura</h2>
 *
 * <p>DP-03 no distingue entre leer y escribir en una historia clinica, y la ficha de un caso lleva
 * diagnostico presuntivo, que es contenido clinico. Las tres lecturas de este servicio dejan
 * evento. Lo que <b>no</b> queda en la auditoria es ese contenido: {@code audit_event} se consulta
 * con {@code auditoria:read}, que no es un permiso clinico, y copiarlo ahi convertiria la
 * auditoria en una via de lectura clinica sin permiso clinico.
 */
@Service
public class CasoClinicoService {

	private static final Logger log = LoggerFactory.getLogger(CasoClinicoService.class);

	private final HistoriaClinicaRepositoryPort historias;
	private final CasoClinicoRepositoryPort casos;
	private final CasoProfesionalRepositoryPort equipos;
	private final CasoEventoRepositoryPort eventos;
	private final CasoNumeradorPort numerador;
	private final CasoNumeradorIniciador numeradorIniciador;
	private final OfertaDirectory ofertas;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public CasoClinicoService(
			HistoriaClinicaRepositoryPort historias,
			CasoClinicoRepositoryPort casos,
			CasoProfesionalRepositoryPort equipos,
			CasoEventoRepositoryPort eventos,
			CasoNumeradorPort numerador,
			CasoNumeradorIniciador numeradorIniciador,
			OfertaDirectory ofertas,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.historias = historias;
		this.casos = casos;
		this.equipos = equipos;
		this.eventos = eventos;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
		this.ofertas = ofertas;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	// =================================================================================
	// RF-M10-001 — abrir
	// =================================================================================

	/**
	 * Abre un caso en esa historia, con su correlativo y su equipo inicial.
	 *
	 * <h2>El orden importa y no es negociable</h2>
	 *
	 * <pre>
	 *   1. historia y autorizacion clinica
	 *   2. oferta vigente           &lt;- RN-M10-006
	 *   3. deteccion de duplicado   &lt;- ANTES de pedir un numero
	 *   4. asegurar el numerador    &lt;- en su PROPIA transaccion
	 *   5. incrementar y leer       &lt;- toma el lock de fila y serializa
	 *   6. escribir caso, equipo y evento
	 * </pre>
	 *
	 * <p><b>El paso 3 va antes del 5</b>, que es la regla 4 del Paquete B: si no, cada alta
	 * rechazada por duplicado consume un correlativo que despues nadie usa, y la numeracion del
	 * paciente queda con huecos que parecen casos borrados.
	 *
	 * @throws CasoClinicoPosibleDuplicadoException si ya hay un caso activo de esa oferta y el
	 *                                              llamador no lo confirmo (409, con candidatos)
	 * @throws OfertaNoVigenteException             si la oferta no existe en la sede o vencio (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CasoClinicoView abrir(
			OperatingActor actor, CasoClinicoAltaCommand command, String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, command.historiaClinicaId());

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Abrir caso clinico");

		long consultorioId = actor.consultorioId();
		exigirOfertaVigente(organizationId, consultorioId, command.ofertaId());

		if (!command.confirmaPosibleDuplicado()) {
			List<Long> candidatos = casos
					.buscarActivosPorOferta(organizationId, historia.getId(), command.ofertaId())
					.stream()
					.map(CasoClinico::getId)
					.toList();
			if (!candidatos.isEmpty()) {
				log.debug("Alta de caso detenida por posible duplicado: candidatos={}",
						candidatos.size());
				throw new CasoClinicoPosibleDuplicadoException(candidatos);
			}
		}

		// PASOS 4 y 5. La fila se asegura AFUERA; el incremento toma el lock y serializa.
		numeradorIniciador.asegurarCasos(organizationId, historia.getId());
		numerador.incrementar(organizationId, historia.getId());
		int numero = numerador.leerUltimo(organizationId, historia.getId());

		Instant ahora = Instant.now();
		CasoClinico caso = casos.save(new CasoClinico(
				organizationId,
				historia.getId(),
				numero,
				command.ofertaId(),
				consultorioId,
				command.diagnosticoPresuntivo(),
				command.objetivoTerapeutico(),
				ahora,
				actor.accountId()));

		List<CasoProfesional> equipo = incorporar(caso, command.equipo(), ahora);
		asentar(caso, TipoEventoCaso.APERTURA, null, EstadoCaso.ACTIVO, null,
				"Caso abierto con " + equipo.size() + " profesional(es)", ahora, actor);

		auditar(AuditEvents.CASO_CLINICO_OPENED, caso.getId(), actor, acceso, null, "ACTIVO",
				Map.of("historiaClinicaId", String.valueOf(historia.getId()),
						"numeroCaso", String.valueOf(numero),
						"ofertaId", String.valueOf(command.ofertaId()),
						"confirmoPosibleDuplicado",
						String.valueOf(command.confirmaPosibleDuplicado())),
				ahora);
		registrarSoporte(acceso, actor, caso, "Abrir caso clinico", ahora);

		log.info("Caso clinico abierto: casoId={} historiaClinicaId={} numeroCaso={}",
				caso.getId(), historia.getId(), numero);
		return CasoClinicoView.de(caso,
				equipo.stream().map(CasoProfesionalView::de).toList());
	}

	// =================================================================================
	// RF-M10-004 — editar
	// =================================================================================

	/**
	 * Cambia el diagnostico presuntivo y el objetivo terapeutico (RF-M10-004).
	 *
	 * <p><b>{@code expectedVersion} no es un chequeo de rutina.</b> Dos profesionales del equipo
	 * editando el objetivo del mismo caso son el caso normal; sin la version, el segundo pisa al
	 * primero en silencio.
	 *
	 * <p>Un caso cerrado responde <b>409</b>, no 403: quien opera tiene el permiso y lo que no
	 * admite cambios es el estado. Lo que corresponde es reabrirlo con motivo, que queda en el
	 * historial (RF-M10-006).
	 */
	@Transactional
	public CasoClinicoView editar(
			OperatingActor actor,
			long casoClinicoId,
			String diagnosticoPresuntivo,
			String objetivoTerapeutico,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_WRITE, justificacion,
				"Editar caso clinico");

		caso.exigirActivo();
		exigirVersion(caso, expectedVersion);

		Instant ahora = Instant.now();
		caso.editar(diagnosticoPresuntivo, objetivoTerapeutico);
		CasoClinico guardado = casos.save(caso);

		asentar(guardado, TipoEventoCaso.EDICION, EstadoCaso.ACTIVO, EstadoCaso.ACTIVO, null,
				"Se edito el contenido clinico del caso", ahora, actor);

		auditar(AuditEvents.CASO_CLINICO_UPDATED, guardado.getId(), actor, acceso, "ACTIVO",
				"ACTIVO", Map.of("historiaClinicaId",
						String.valueOf(guardado.getHistoriaClinicaId())), ahora);
		registrarSoporte(acceso, actor, guardado, "Editar caso clinico", ahora);

		return CasoClinicoView.de(guardado, equipoVigenteDe(guardado));
	}

	// =================================================================================
	// RF-M10-006 — cerrar y reabrir
	// =================================================================================

	/**
	 * Cierra el caso con motivo declarado (RF-M10-006).
	 *
	 * <p><b>Cerrar no es borrar.</b> El caso se sigue leyendo entero, con todo su historial: no hay
	 * baja logica de caso y no la va a haber, porque {@code active} al lado de {@code estado} daria
	 * dos formas de que un caso "no este".
	 *
	 * <p><b>Cerrar dos veces no es un conflicto</b>: es el mismo pedido, se responde con el caso
	 * tal como quedo y el motivo original queda intacto. Pisarlo con el nuevo perderia el que
	 * explica el cierre — mismo criterio que la baja de una entrada clinica. Y por eso la
	 * idempotencia se evalua <b>antes</b> de exigir la version: reintentar un cierre que ya ocurrio
	 * no puede fallar por una version que avanzo justamente por ese cierre.
	 */
	@Transactional
	public CasoClinicoView cerrar(
			OperatingActor actor,
			long casoClinicoId,
			String motivo,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_WRITE, justificacion,
				"Cerrar caso clinico");

		Instant ahora = Instant.now();
		if (!caso.estaActivo()) {
			log.debug("Caso clinico ya cerrado: casoId={}", casoClinicoId);
			return CasoClinicoView.de(caso, equipoVigenteDe(caso));
		}
		exigirVersion(caso, expectedVersion);

		caso.cerrar(motivo, ahora, actor.accountId());
		CasoClinico guardado = casos.save(caso);

		asentar(guardado, TipoEventoCaso.CIERRE, EstadoCaso.ACTIVO, EstadoCaso.CERRADO,
				guardado.getMotivoCierre(), null, ahora, actor);

		auditar(AuditEvents.CASO_CLINICO_CLOSED, guardado.getId(), actor, acceso, "ACTIVO",
				"CERRADO", Map.of("historiaClinicaId",
						String.valueOf(guardado.getHistoriaClinicaId())), ahora);
		registrarSoporte(acceso, actor, guardado, "Cerrar caso clinico", ahora);

		log.info("Caso clinico cerrado: casoId={}", guardado.getId());
		return CasoClinicoView.de(guardado, equipoVigenteDe(guardado));
	}

	/**
	 * Reabre un caso cerrado, con motivo (RF-M10-006).
	 *
	 * <p>El estado vuelve a {@code ACTIVO} y no aparece ningun estado nuevo: RF-M10-006 pide
	 * reabrir, no un tercer valor. Lo que hace que la reapertura sea revisable despues es el
	 * evento, no una columna.
	 *
	 * <p><b>No reinicia la numeracion de sesiones del caso.</b> La sesion siguiente es la 9, no la
	 * 1: renumerar seria reescribir historia clinica (challenge, quinta condicion).
	 */
	@Transactional
	public CasoClinicoView reabrir(
			OperatingActor actor,
			long casoClinicoId,
			String motivo,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_WRITE, justificacion,
				"Reabrir caso clinico");

		Instant ahora = Instant.now();
		if (caso.estaActivo()) {
			log.debug("Caso clinico ya activo: casoId={}", casoClinicoId);
			return CasoClinicoView.de(caso, equipoVigenteDe(caso));
		}
		exigirVersion(caso, expectedVersion);

		caso.reabrir(motivo, ahora);
		CasoClinico guardado = casos.save(caso);

		asentar(guardado, TipoEventoCaso.REAPERTURA, EstadoCaso.CERRADO, EstadoCaso.ACTIVO,
				motivo, null, ahora, actor);

		auditar(AuditEvents.CASO_CLINICO_REOPENED, guardado.getId(), actor, acceso, "CERRADO",
				"ACTIVO", Map.of("historiaClinicaId",
						String.valueOf(guardado.getHistoriaClinicaId())), ahora);
		registrarSoporte(acceso, actor, guardado, "Reabrir caso clinico", ahora);

		log.info("Caso clinico reabierto: casoId={}", guardado.getId());
		return CasoClinicoView.de(guardado, equipoVigenteDe(guardado));
	}

	// =================================================================================
	// RF-M10-005 — equipo
	// =================================================================================

	/**
	 * Reemplaza el equipo tratante por el que se declara (RF-M10-005).
	 *
	 * <h2>Lo que sale NO se borra</h2>
	 *
	 * <p>A quien deja de estar en la lista se le pone {@code hasta}; su fila queda. Un profesional
	 * desvinculado sigue figurando en el caso que trato, <b>porque lo trato</b>, y borrarlo de la
	 * lista reescribiria historia (regla maestra 10). A quien vuelve se le abre una participacion
	 * nueva: la anterior termino y sigue siendo cierta.
	 *
	 * <h2>El control optimista aca es distinto del resto del servicio</h2>
	 *
	 * <p>Esta operacion <b>no toca ni una columna de {@code caso_clinico}</b>: escribe en
	 * {@code caso_profesional}. Un {@code @Version} sobre el padre no protege una escritura que
	 * solo toca tablas hijas —la leccion de 02.07, pagada con un 409 que no aparecia nunca— asi
	 * que el caso se lee con {@code OPTIMISTIC_FORCE_INCREMENT} y su version avanza al commitear.
	 * Dos cambios de equipo concurrentes no pueden commitear los dos.
	 */
	@Transactional
	public CasoClinicoView cambiarEquipo(
			OperatingActor actor,
			long casoClinicoId,
			List<IntegranteDelEquipo> integrantes,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = casos
				.findWithLockByIdAndOrganizationId(casoClinicoId, organizationId)
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(casoClinicoId));
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_WRITE, justificacion,
				"Cambiar el equipo de un caso clinico");

		caso.exigirActivo();
		exigirVersion(caso, expectedVersion);

		Instant ahora = Instant.now();
		Map<Long, RolEnCaso> pedidos = new LinkedHashMap<>();
		for (IntegranteDelEquipo integrante : integrantes == null ? List.<IntegranteDelEquipo>of()
				: integrantes) {
			pedidos.put(integrante.profesionalMembershipId(), integrante.rol());
		}

		List<CasoProfesional> vigentes = equipos.buscarDeCaso(organizationId, caso.getId(), true);
		Set<Long> yaEstaban = new LinkedHashSet<>();
		int salieron = 0;
		for (CasoProfesional participacion : vigentes) {
			Long membershipId = participacion.getProfesionalMembershipId();
			if (pedidos.containsKey(membershipId)) {
				yaEstaban.add(membershipId);
				participacion.cambiarRol(pedidos.get(membershipId));
			} else {
				participacion.desvincular(ahora);
				salieron++;
			}
		}
		equipos.saveAll(vigentes);

		List<IntegranteDelEquipo> nuevos = pedidos.entrySet().stream()
				.filter(entrada -> !yaEstaban.contains(entrada.getKey()))
				.map(entrada -> new IntegranteDelEquipo(entrada.getKey(), entrada.getValue()))
				.toList();
		incorporar(caso, nuevos, ahora);

		// El caso se guarda aunque no cambie ninguna de sus columnas: es lo que materializa el
		// OPTIMISTIC_FORCE_INCREMENT de la lectura y hace avanzar su version.
		CasoClinico guardado = casos.save(caso);

		asentar(guardado, TipoEventoCaso.CAMBIO_DE_EQUIPO, guardado.getEstado(),
				guardado.getEstado(), null,
				"Entraron " + nuevos.size() + ", salieron " + salieron, ahora, actor);

		auditar(AuditEvents.CASO_EQUIPO_CHANGED, guardado.getId(), actor, acceso, null, null,
				Map.of("historiaClinicaId", String.valueOf(guardado.getHistoriaClinicaId()),
						"entraron", String.valueOf(nuevos.size()),
						"salieron", String.valueOf(salieron)),
				ahora);
		registrarSoporte(acceso, actor, guardado, "Cambiar el equipo de un caso clinico", ahora);

		return CasoClinicoView.de(guardado, equipoVigenteDe(guardado));
	}

	// =================================================================================
	// Lecturas — las tres dejan evento de auditoria
	// =================================================================================

	/** Un caso con su equipo vigente. Es lectura clinica y se audita como tal. */
	@Transactional
	public CasoClinicoView ver(OperatingActor actor, long casoClinicoId, String justificacion) {
		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_READ, justificacion,
				"Ver caso clinico");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, caso, "CASO", ahora);
		registrarSoporte(acceso, actor, caso, "Ver caso clinico", ahora);

		return CasoClinicoView.de(caso, equipoVigenteDe(caso));
	}

	/**
	 * Los casos de una historia, mas recientes primero.
	 *
	 * @param soloActivos {@code true} deja afuera los cerrados; {@code false} los incluye, que es
	 *                    lo que hace consultable el historico del paciente. Un caso cerrado no es
	 *                    un caso borrado
	 */
	@Transactional
	public List<CasoClinicoView> listar(
			OperatingActor actor,
			long historiaClinicaId,
			boolean soloActivos,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Listar casos clinicos");

		Instant ahora = Instant.now();
		auditTrail.record(entrada(AuditEvents.CASO_CLINICO_ACCESSED,
				AuditEvents.ENTITY_HISTORIA_CLINICA, historia.getId(), actor, acceso, null, null,
				Map.of("personaId", String.valueOf(historia.getPersonaId()),
						"alcance", "CASOS",
						"soloActivos", String.valueOf(soloActivos)),
				ahora));

		return casos.buscarDeHistoria(organizationId, historia.getId(), soloActivos).stream()
				.map(caso -> CasoClinicoView.de(caso, equipoVigenteDe(caso)))
				.toList();
	}

	/**
	 * El historial de estados de un caso, del mas viejo al mas nuevo (RF-M10-006).
	 *
	 * <p>Es su propia operacion y su propio evento de auditoria, por lo mismo que el historico de
	 * versiones de una entrada: muestra lo que le paso al caso y por que, y colapsarlo dentro de la
	 * lectura normal esconderia un acceso que despues alguien quiere revisar.
	 */
	@Transactional
	public List<CasoEventoView> eventos(
			OperatingActor actor, long casoClinicoId, String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_READ, justificacion,
				"Ver el historial de un caso clinico");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, caso, "CASO_EVENTOS", ahora);
		registrarSoporte(acceso, actor, caso, "Ver el historial de un caso clinico", ahora);

		return eventos.buscarDeCaso(organizationId, caso.getId()).stream()
				.map(CasoEventoView::de)
				.toList();
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/**
	 * La organizacion del contexto, exigiendo que haya contexto.
	 *
	 * <p>Misma primera condicion que {@link AutorizacionClinica}, repetida por el mismo motivo que
	 * en {@code EntradaClinicaService}: las operaciones sobre un caso llegan con el id del caso y
	 * no con el de la persona, asi que hay que resolver de quien es la historia antes de poder
	 * preguntar por relacion asistencial, y esa resolucion ya necesita el tenant.
	 */
	private long organizacionDe(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Operacion sobre casos clinicos sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private HistoriaClinica exigirHistoria(long organizationId, long historiaClinicaId) {
		return historias.findByIdAndOrganizationId(historiaClinicaId, organizationId)
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(historiaClinicaId));
	}

	private CasoClinico exigirCaso(long organizationId, long casoClinicoId) {
		return casos.findByIdAndOrganizationId(casoClinicoId, organizationId)
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(casoClinicoId));
	}

	/**
	 * La autorizacion clinica de una operacion que llego con el id del caso.
	 *
	 * <p>Resuelve caso -&gt; historia -&gt; persona antes de evaluar, que es lo que permite que el
	 * caso viva en ruta plana. Si la historia del caso no resuelve, el caso <b>no es accesible</b>:
	 * es una fila que apunta a una historia que no existe o esta dada de baja, y responder 404
	 * sobre el caso es mas honesto que hablar de una historia que el cliente no nombro.
	 */
	private AccesoClinico autorizarSobre(
			OperatingActor actor,
			CasoClinico caso,
			String permissionCode,
			String justificacion,
			String operacion) {

		HistoriaClinica historia = historias
				.findByIdAndOrganizationId(caso.getHistoriaClinicaId(), caso.getOrganizationId())
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(caso.getId()));

		return AutorizacionClinica.exigir(permissionGuard, relaciones, actor, permissionCode,
				historia.getPersonaId(), justificacion, operacion);
	}

	/**
	 * La oferta que motiva el caso tiene que existir en la sede y estar vigente hoy (RN-M10-006).
	 *
	 * <p>Se evalua contra la fecha de <b>hoy en UTC</b> y no contra la zona de la sede, y la
	 * diferencia es conocida: una oferta que vence hoy podria aceptarse unas horas de mas o de
	 * menos segun el huso. Se asume porque la alternativa —traer la zona IANA de la sede para
	 * decidir el alta de un caso— acopla este servicio a {@code organization} por un borde que no
	 * cambia ninguna decision clinica. Donde la zona SI importa es en la agenda, y ahi ya se usa.
	 */
	private void exigirOfertaVigente(long organizationId, long consultorioId, long ofertaId) {
		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNoVigenteException(ofertaId));
		if (!oferta.vigenteEl(LocalDate.now(ZoneOffset.UTC))) {
			throw new OfertaNoVigenteException(ofertaId);
		}
	}

	/** Abre una participacion por cada integrante declarado. Devuelve las filas escritas. */
	private List<CasoProfesional> incorporar(
			CasoClinico caso, List<IntegranteDelEquipo> integrantes, Instant ahora) {

		return equipos.saveAll(integrantes.stream()
				.map(integrante -> new CasoProfesional(
						caso.getOrganizationId(),
						caso.getId(),
						integrante.profesionalMembershipId(),
						integrante.rol(),
						ahora))
				.toList());
	}

	private List<CasoProfesionalView> equipoVigenteDe(CasoClinico caso) {
		return equipos.buscarDeCaso(caso.getOrganizationId(), caso.getId(), true).stream()
				.map(CasoProfesionalView::de)
				.toList();
	}

	/**
	 * Escribe la transicion en el historial del caso, <b>dentro</b> de la misma transaccion.
	 *
	 * <p>No es post-commit y no puede serlo: un evento escrito despues del commit puede perderse y
	 * dejar la transicion sin rastro, que es exactamente el agujero que este historial existe para
	 * tapar. Mismo criterio que {@code turno_evento} en 05.03.
	 *
	 * <p>{@code detalle} nunca lleva diagnostico ni objetivo. Este historial lo lee el profesional
	 * que abre la ficha; el contenido clinico se lee del caso, con su propio acceso auditado.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private void asentar(
			CasoClinico caso,
			TipoEventoCaso tipo,
			EstadoCaso anterior,
			EstadoCaso nuevo,
			String motivo,
			String detalle,
			Instant ahora,
			OperatingActor actor) {

		eventos.save(new CasoEvento(
				caso.getOrganizationId(),
				caso.getId(),
				tipo,
				anterior,
				nuevo,
				motivo,
				detalle,
				ahora,
				actor.accountId()));
	}

	/**
	 * El control optimista, en un solo lugar.
	 *
	 * <p>Se lanza el mismo tipo que JPA usaria para que el handler global lo mapee igual y el
	 * cliente vea un solo comportamiento; la diferencia es que aca se detecta antes de escribir,
	 * con un mensaje que nombra la situacion.
	 */
	private static void exigirVersion(CasoClinico caso, long expectedVersion) {
		if (caso.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El caso clinico " + caso.getId() + " cambio desde que se leyo: volve a "
							+ "abrirlo antes de guardar");
		}
	}

	private void auditarLectura(
			OperatingActor actor,
			AccesoClinico acceso,
			CasoClinico caso,
			String alcance,
			Instant ahora) {

		auditTrail.record(entrada(AuditEvents.CASO_CLINICO_ACCESSED,
				AuditEvents.ENTITY_CASO_CLINICO, caso.getId(), actor, acceso, null, null,
				Map.of("historiaClinicaId", String.valueOf(caso.getHistoriaClinicaId()),
						"alcance", alcance),
				ahora));
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void auditar(
			String eventType,
			Long entityId,
			OperatingActor actor,
			AccesoClinico acceso,
			String previo,
			String nuevo,
			Map<String, String> detalles,
			Instant ahora) {

		auditTrail.record(entrada(eventType, AuditEvents.ENTITY_CASO_CLINICO, entityId, actor,
				acceso, previo, nuevo, detalles, ahora));
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private static AuditEntry entrada(
			String eventType,
			String entityType,
			Long entityId,
			OperatingActor actor,
			AccesoClinico acceso,
			String previo,
			String nuevo,
			Map<String, String> detalles,
			Instant ahora) {

		Map<String, String> conVia = new LinkedHashMap<>(detalles);
		conVia.put("viaDeAcceso", acceso.via());

		return new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				entityType,
				entityId,
				previo,
				nuevo,
				conVia,
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora);
	}

	private void registrarSoporte(
			AccesoClinico acceso,
			OperatingActor actor,
			CasoClinico caso,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				caso.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_CASO_CLINICO,
				caso.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
