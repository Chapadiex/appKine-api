package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El timeline longitudinal de una Historia Clinica (RF-M09-004).
 *
 * <h2>No hay tabla de timeline, y esa es la decision central de la etapa</h2>
 *
 * <p>La pagina se calcula al leer, agregando lo que aporta cada
 * {@link EventoClinicoContributor}. Una tabla seria una segunda copia de la verdad: el dia que
 * alguien enmiende una entrada, de de baja un adjunto o anule una sesion sin avisarle al
 * proyector, esa copia miente y nadie se entera. Es el mismo argumento con el que 05.01 no
 * persiste slots.
 *
 * <p><b>El costo esta asumido y conviene tenerlo escrito:</b> una pagina cuesta una consulta por
 * fuente, y hoy son cuatro. Crece linealmente con cada modulo que aporte. El tope por
 * contribuyente lo acota; el dia que sean ocho y el percentil 95 se note, la respuesta es una
 * proyeccion — y recien ahi una tabla con su escritor.
 *
 * <h2>Un indice, no un visor</h2>
 *
 * <p>Ningun evento lleva texto de evolucion, diagnostico ni medicion: solo la etiqueta del tipo de
 * hecho y la referencia para ir a buscarlo. Quien quiera el detalle va al modulo dueno con su
 * propio permiso, y ese acceso se audita alli. Si el timeline trajera contenido, una sola lectura
 * entregaria la historia entera y los eventos de acceso de cada modulo dejarian de significar
 * algo.
 *
 * <h2>El recepcionista que abre la ficha desde el mostrador</h2>
 *
 * <p>Pasa, y pasa a proposito. {@code hc:read} con justificacion declarada lo deja entrar y lo
 * <b>audita</b>, que es deliberadamente mas debil que negarlo por rol: negar por rol rompe la
 * recepcion real de un centro. El control es {@link AuditEvents#TIMELINE_ACCESSED}, y por eso no
 * es opcional — el timeline le muestra a quien lo abre cuantos hechos clinicos tiene un paciente y
 * de que clase, que ya es informacion sensible aunque no traiga contenido (DP-03).
 */
@Service
public class TimelineService {

	private static final Logger log = LoggerFactory.getLogger(TimelineService.class);

	/** Cuantos eventos trae una pagina cuando el cliente no pide un tamano. */
	static final int LIMITE_POR_DEFECTO = 50;

	/**
	 * Tope duro de tamano de pagina.
	 *
	 * <p>Lo decide el servidor y no el cliente: un {@code limite=100000} convertiria el endpoint
	 * mas usado del modulo en cuatro consultas sin cota sobre la historia entera de un paciente
	 * cronico. Un pedido mayor se recorta en silencio en vez de rechazarse — el cliente que pide
	 * de mas igual recibe una pagina valida y su cursor.
	 */
	static final int LIMITE_MAXIMO = 100;

	private final HistoriaClinicaRepositoryPort historias;
	private final CasoClinicoRepositoryPort casos;
	private final List<EventoClinicoContributor> contribuyentes;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	public TimelineService(
			HistoriaClinicaRepositoryPort historias,
			CasoClinicoRepositoryPort casos,
			List<EventoClinicoContributor> contribuyentes,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.historias = historias;
		this.casos = casos;
		this.contribuyentes = List.copyOf(contribuyentes);
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	/**
	 * Una pagina del timeline de esa historia, mas nuevos primero.
	 *
	 * <h2>Como se arma la pagina</h2>
	 *
	 * <ol>
	 *   <li>Se le pide a cada contribuyente hasta {@code limite + 1} eventos con
	 *       {@code ocurrioEn <= hasta}, donde {@code hasta} es el instante del cursor o ahora.
	 *       <b>La sobre-lectura es deliberada</b>: es lo unico que permite saber si hay pagina
	 *       siguiente sin una segunda consulta por fuente.</li>
	 *   <li>Se mezcla todo, se descarta lo que no sea <b>estrictamente posterior</b> al cursor en
	 *       el orden total {@code (ocurrioEn DESC, origen ASC, referencia DESC)} —el tope temporal
	 *       es inclusivo, asi que los empates del instante del cursor llegan y se filtran aca— y
	 *       se ordena.</li>
	 *   <li>Se recorta a {@code limite}. Si sobro algo, hay pagina siguiente y su cursor es el
	 *       ultimo evento entregado.</li>
	 * </ol>
	 *
	 * <h2>El borde que esto deja abierto, dicho de frente</h2>
	 *
	 * <p>Si un solo contribuyente tiene mas de {@code limite} eventos <b>en el mismo instante
	 * exacto</b> que el cursor, la pagina siguiente puede saltear alguno. Es improbable
	 * —{@code ocurrioEn} es {@code DATETIME(6)}— y el precio de cerrarlo es un {@code WHERE}
	 * lexicografico de tres columnas replicado en cada fuente, que es la clase de complejidad que
	 * despues nadie mantiene igual en las cuatro. <b>Queda declarado, no resuelto</b> (diseno
	 * seccion 2.1).
	 *
	 * @param cursorCrudo cursor opaco devuelto por la pagina anterior, o {@code null} para la
	 *                    primera. Uno que no decodifique es 400, nunca "primera pagina"
	 * @param limite      tamano de pagina pedido. {@code null} o menor a 1 usa el default; mayor
	 *                    al tope se recorta
	 * @param casoId      filtro opcional por Caso Clinico (04.03). {@code null} no filtra. <b>Es un
	 *                    parametro mas para los contribuyentes, no una consulta nueva</b>: agregar
	 *                    una tabla o una proyeccion por caso seria la segunda copia de la verdad
	 *                    que esta etapa y la anterior se negaron a crear. El caso se valida contra
	 *                    la historia antes de filtrar: uno que no es de esa historia responde 404 y
	 *                    no una pagina vacia, que es lo que distingue "no hay hechos" de "ese caso
	 *                    no es de este paciente"
	 * @throws CasoClinicoNotAccessibleException si se filtra por un caso que no es de esa historia
	 */
	@Transactional
	public TimelinePagina ver(
			OperatingActor actor,
			long historiaClinicaId,
			String cursorCrudo,
			Integer limite,
			Long casoId,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = historias
				.findByIdAndOrganizationId(historiaClinicaId, organizationId)
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(historiaClinicaId));

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Ver timeline clinico");

		// Igual que el cursor: el caso se valida DESPUES de autorizar, o un 404 sobre un id de caso
		// ajeno seria un oraculo de que casos existen en este tenant.
		exigirCasoDeLaHistoria(organizationId, historia.getId(), casoId);

		// El cursor se decodifica DESPUES de autorizar: un 400 por cursor roto antes de evaluar el
		// permiso le confirmaria a cualquiera que esa historia existe en este tenant.
		TimelineCursor cursor = TimelineCursor.decodificar(cursorCrudo);
		int tamano = tamanoDePagina(limite);
		Instant hasta = cursor == null ? Instant.now() : cursor.ocurrioEn();

		// tamano + 2 Y NO tamano + 1, Y SIN ESE UNO DE MAS LA PAGINACION PIERDE EVENTOS.
		//
		// Cada fuente filtra por `ocurrio_en <= hasta`, y cuando hay cursor ese `hasta` es el
		// instante del ULTIMO evento devuelto, asi que la fuente a la que el cursor apunta trae ese
		// mismo evento otra vez y el `precedeA` de abajo lo descarta. Con `tamano + 1`, el uno de mas
		// —el que existe para saber si hay pagina siguiente— se lo come justamente esa fila: la
		// mezcla queda en `tamano` exacto, `hayMas` da falso y el recorrido termina ahi, dejando
		// afuera todo lo mas viejo. El endpoint responde 200 y el cliente no tiene forma de notarlo.
		//
		// Lo encontro `TimelineIT#el_cursor_no_repite_ni_saltea` en la primera corrida real: seis
		// hechos recorridos de dos en dos devolvian cuatro. Es el mismo defecto para cualquier
		// historia con mas de `limite` hechos, o sea cualquier paciente cronico.
		int conLookahead = tamano + 2;

		List<EventoClinico> mezcla = new ArrayList<>();
		for (EventoClinicoContributor contribuyente : contribuyentes) {
			for (EventoClinico evento : contribuyente.eventosDe(
					organizationId, historia.getId(), hasta, conLookahead, casoId)) {

				if (cursor == null || cursor.precedeA(evento)) {
					mezcla.add(evento);
				}
			}
		}
		mezcla.sort(TimelineCursor.ORDEN);

		boolean hayMas = mezcla.size() > tamano;
		List<EventoClinico> pagina = List.copyOf(mezcla.subList(0, Math.min(tamano, mezcla.size())));
		String proximoCursor = hayMas && !pagina.isEmpty()
				? TimelineCursor.de(pagina.get(pagina.size() - 1)).codificar()
				: null;

		auditar(actor, acceso, historia, pagina.size(), cursor != null, Instant.now());
		registrarSoporte(acceso, actor, historia, Instant.now());

		log.debug("Timeline leido: historiaClinicaId={} eventos={} fuentes={}",
				historia.getId(), pagina.size(), contribuyentes.size());
		return new TimelinePagina(pagina, proximoCursor);
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/**
	 * La organizacion del contexto, exigiendo que haya contexto.
	 *
	 * <p>Misma primera condicion que {@link AutorizacionClinica}, repetida por el mismo motivo que
	 * en {@code EntradaClinicaService}: el pedido llega con el id de la historia y no con el de la
	 * persona, asi que hay que resolver de quien es antes de poder preguntar por relacion
	 * asistencial, y esa resolucion ya necesita el tenant.
	 */
	private long organizacionDe(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Lectura de timeline sin contexto validado: accountId={}", actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * Exige que el caso por el que se filtra sea de esa historia.
	 *
	 * <p>Sin esta validacion, filtrar por un caso ajeno devolveria una pagina vacia y la pantalla
	 * mostraria "este caso no tiene hechos" sobre un caso que ni siquiera es del paciente. Son dos
	 * situaciones distintas y llevan a acciones distintas.
	 *
	 * <p>Se resuelve por el mismo puerto que usa {@code CasoClinicoService} y <b>no</b> por el spi:
	 * es el propio modulo leyendo su propia tabla.
	 */
	private void exigirCasoDeLaHistoria(long organizationId, long historiaClinicaId, Long casoId) {
		if (casoId == null) {
			return;
		}
		casos.findByIdAndOrganizationId(casoId, organizationId)
				.filter(caso -> caso.perteneceAHistoria(historiaClinicaId))
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(casoId));
	}

	private static int tamanoDePagina(Integer limite) {
		if (limite == null || limite < 1) {
			return LIMITE_POR_DEFECTO;
		}
		return Math.min(limite, LIMITE_MAXIMO);
	}

	/**
	 * El evento de lectura del timeline.
	 *
	 * <p>Los detalles dicen <b>cuantos</b> eventos se entregaron y si fue la primera pagina o una
	 * siguiente, que es lo que permite reconstruir despues si alguien recorrio la historia entera
	 * de un paciente o solo abrio la ficha. Lo que no va es ningun id de evento: la auditoria se
	 * consulta con {@code auditoria:read}, que no es un permiso clinico, y una lista de hechos
	 * clinicos de un paciente ahi seria una via de lectura clinica sin permiso clinico.
	 */
	private void auditar(
			OperatingActor actor,
			AccesoClinico acceso,
			HistoriaClinica historia,
			int eventos,
			boolean paginado,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				AuditEvents.TIMELINE_ACCESSED,
				AuditEvents.ENTITY_HISTORIA_CLINICA,
				historia.getId(),
				null,
				null,
				Map.of("personaId", String.valueOf(historia.getPersonaId()),
						"eventos", String.valueOf(eventos),
						"pagina", paginado ? "SIGUIENTE" : "PRIMERA",
						"viaDeAcceso", acceso.via()),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}

	private void registrarSoporte(
			AccesoClinico acceso, OperatingActor actor, HistoriaClinica historia, Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				historia.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_HISTORIA_CLINICA,
				historia.getId(),
				null,
				null,
				Map.of("operacion", "Ver timeline clinico"),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
