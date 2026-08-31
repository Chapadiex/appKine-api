package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;

import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.DisponibilidadDirectory.DiaDisponible;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.application.AgendaView.DiaDeAgenda;
import com.akine.scheduling.application.AgendaView.SlotDisponible;
import com.akine.scheduling.domain.DiaDeSlots;
import com.akine.scheduling.domain.MotivoSinSlots;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Slot;
import com.akine.scheduling.domain.SlotGenerator;
import com.akine.scheduling.domain.TramoLocal;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.spi.ReservaProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de slots: que turnos concretos ofrece una oferta en una ventana (M12, RF-M12-001).
 *
 * <h2>Nada de esto se persiste</h2>
 *
 * <p>Un slot se calcula al leer y se descarta. Guardarlo lo haria envejecer —cambia un horario,
 * entra un feriado, se desvincula el profesional— y a partir de ese momento la agenda ofreceria
 * huecos que ya no existen sin que nada falle. Es la misma regla que 02.04 fijo para la
 * disponibilidad efectiva, y la razon por la que esta etapa no crea ninguna tabla.
 *
 * <h2>La composicion, y por que este orden</h2>
 *
 * <pre>
 *   1. sede + huso            -&gt; 404 si es de otro tenant, ANTES del permiso
 *   2. turno:read             -&gt; 403
 *   3. ventana acotada        -&gt; 400  (tope propio, mas chico que el de M05: ver VentanaDeAgenda)
 *   4. oferta de ESA sede     -&gt; 404
 *   5. oferta agendable       -&gt; 409 si esta de baja o su vigencia no toca la ventana
 *   6. habilitaciones         -&gt; profesionales y espacios de 02.07
 *   7. disponibilidad         -&gt; una llamada por profesional habilitado, ventana entera
 *   8. corte en slots         -&gt; puro, por dia y por profesional
 *   9. descuento de reservas  -&gt; ReservaProbe; hoy no descuenta nada
 * </pre>
 *
 * <p><b>El paso 3 despues del 2</b>, igual que en M05: un 400 antes del permiso le confirma a
 * cualquiera que esa sede existe.
 *
 * <h2>Los tres filtros que se evaluan DIA POR DIA y no contra la ventana</h2>
 *
 * <p>La vigencia de la oferta, la de cada habilitacion y la del vinculo del profesional —esta
 * ultima la aplica M05 adentro—. Es el ruling R13 de 02.04 extendido a dos cosas mas: una oferta
 * que vence el 15 no puede ofrecer turnos el 20 porque la consulta abarco todo el mes, y lo mismo
 * vale para una habilitacion dada de baja a mitad de mes. El control grueso sobrevive como atajo
 * —una oferta que no toca la ventana sale por 409 sin leer una sola habilitacion— pero nunca es
 * el unico.
 *
 * <h2>El espacio no se clava aca, y es deliberado</h2>
 *
 * <p>Cuando la oferta requiere espacio, este motor verifica que exista <b>al menos uno</b>
 * habilitado, en servicio y vigente ese dia; no elige cual. Elegirlo obligaria a simular la
 * asignacion de todos los slots del dia contra la capacidad de cada box, que es exactamente el
 * trabajo transaccional de 05.02 — y hacerlo aca, fuera de la transaccion que crea el turno, seria
 * una promesa que dos busquedas concurrentes rompen: las dos verian el mismo box libre.
 *
 * <h2>Lo que este motor NO garantiza</h2>
 *
 * <p>Que un slot devuelto se pueda reservar. Entre esta lectura y la escritura de 05.02 puede
 * entrar cualquier cosa. La exclusion real es de la reserva, con su lock; si 05.02 confiara en
 * este resultado, dos recepcionistas mirando la misma pantalla venden el mismo turno.
 */
@Service
public class AgendaService {

	private static final Logger log = LoggerFactory.getLogger(AgendaService.class);

	private final OfertaDirectory ofertas;
	private final DisponibilidadDirectory disponibilidad;
	private final EspacioDirectory espacios;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final ReservaProbe reservas;

	public AgendaService(
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			ReservaProbe reservas) {

		this.ofertas = ofertas;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.reservas = reservas;
	}

	/**
	 * Slots disponibles de una oferta entre {@code desde} y {@code hasta}.
	 *
	 * @param hasta         fecha local <b>EXCLUSIVA</b>
	 * @param profesionalId para acotar a un solo profesional habilitado, o {@code null} para todos
	 * @throws ConsultorioNoAccesibleException si la sede no existe o es de otro tenant (404)
	 * @throws OfertaNotAccessibleException    si la oferta no existe en esa sede (404)
	 * @throws OfertaNoAgendableException      si la oferta no puede agendar en ningun dia (409)
	 * @throws AccessDeniedException           si falta contexto de tenant (403, nunca 401)
	 */
	@Transactional(readOnly = true)
	public AgendaView buscar(
			OperatingActor actor,
			long consultorioId,
			long ofertaId,
			LocalDate desde,
			LocalDate hasta,
			Long profesionalId) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);

		VentanaDeAgenda.exigirValida(desde, hasta);

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));

		if (oferta.noAgendableEn(desde, hasta)) {
			// 409 y no 404: la oferta existe y quien pregunta la esta viendo en la lista. Un 404
			// mandaria a la pantalla a decir "no encontrada" sobre algo que el usuario tiene
			// delante de los ojos.
			throw new OfertaNoAgendableException(ofertaId,
					oferta.active() ? "su vigencia no toca la ventana pedida" : "esta dada de baja");
		}

		ZoneId zona = ZoneId.of(sede.timezone());
		Duration duracion = Duration.ofMinutes(oferta.duracionMinutos());

		List<HabilitacionSnapshot> profesionales = oferta.requiereProfesional()
				? filtrarPorProfesional(
						ofertas.profesionalesHabilitados(organizationId, consultorioId, ofertaId),
						profesionalId)
				: List.of();

		List<HabilitacionSnapshot> espaciosHabilitados = oferta.requiereEspacio()
				? ofertas.espaciosHabilitados(organizationId, consultorioId, ofertaId)
				: List.of();

		// La disponibilidad se pide UNA vez por profesional y para la ventana entera, no una vez
		// por dia. Cada llamada lee bloques, excepciones y feriados; pedirla por dia multiplicaria
		// esas lecturas por la cantidad de dias sin traer un solo dato nuevo.
		Map<Long, Map<LocalDate, DiaDisponible>> porProfesional = new LinkedHashMap<>();
		for (HabilitacionSnapshot habilitacion : profesionales) {
			porProfesional.put(habilitacion.recursoId(), indexarPorFecha(
					disponibilidad.efectiva(
							organizationId, sede, habilitacion.recursoId(), desde, hasta)));
		}

		List<DiaDeAgenda> dias = new ArrayList<>();
		for (LocalDate fecha = desde; fecha.isBefore(hasta); fecha = fecha.plusDays(1)) {
			DiaDeSlots dia = resolver(
					fecha, oferta, zona, duracion, profesionales, espaciosHabilitados,
					porProfesional, organizationId, consultorioId);
			dias.add(proyectar(dia, fecha, zona));
		}

		log.info("Agenda consultada: consultorioId={} ofertaId={} ventana={}..{} dias={}",
				consultorioId, ofertaId, desde, hasta, dias.size());

		return new AgendaView(
				oferta.id(),
				oferta.consultorioId(),
				oferta.nombreComercial(),
				oferta.duracionMinutos(),
				zona.getId(),
				dias);
	}

	// =================================================================================
	// Composicion de un dia
	// =================================================================================

	/**
	 * Resuelve un dia: sus slots, o el motivo por el que no tiene ninguno.
	 *
	 * <p>El orden de los descartes es el orden en que un humano los explicaria, de lo mas general
	 * a lo mas especifico: primero la oferta, despues los recursos, despues el horario, y recien
	 * al final el tamano de la franja. Devolver el <b>primer</b> motivo que aplica —y no una
	 * lista— es a proposito: la pantalla muestra una frase, y "no hay turnos porque la oferta
	 * vencio y ademas no hay box" no ayuda a nadie a arreglar nada.
	 */
	private DiaDeSlots resolver(
			LocalDate fecha,
			OfertaSnapshot oferta,
			ZoneId zona,
			Duration duracion,
			List<HabilitacionSnapshot> profesionales,
			List<HabilitacionSnapshot> espaciosHabilitados,
			Map<Long, Map<LocalDate, DiaDisponible>> porProfesional,
			long organizationId,
			long consultorioId) {

		if (!oferta.vigenteEl(fecha)) {
			return DiaDeSlots.sin(fecha, MotivoSinSlots.OFERTA_NO_VIGENTE);
		}

		// El MEDIODIA local y no la medianoche: es el instante del dia que ningun cambio de
		// horario de verano puede borrar. Con la medianoche, el dia en que el reloj se adelanta
		// esa hora local no existe y la conversion se corre sola, asi que preguntar "esta vigente
		// este dia" contestaria por el dia equivocado una vez al ano y en un solo huso.
		Instant mediodia = fecha.atTime(12, 0).atZone(zona).toInstant();

		if (oferta.requiereEspacio()
				&& !hayEspacio(espaciosHabilitados, organizationId, consultorioId, mediodia)) {
			return DiaDeSlots.sin(fecha, MotivoSinSlots.SIN_ESPACIO);
		}

		if (!oferta.requiereProfesional()) {
			// Sin profesional no hay disponibilidad que consultar: la oferta se prestaria durante
			// el horario general de la sede, que es una columna que F5 todavia no llena. No se
			// inventa una franja; se declara el motivo, que es lo unico honesto que se puede
			// devolver y ademas distingue este caso de un bug.
			return DiaDeSlots.sin(fecha, MotivoSinSlots.SIN_HORARIO);
		}

		List<HabilitacionSnapshot> vigentes = profesionales.stream()
				.filter(habilitacion -> habilitacion.vigenteEn(mediodia))
				.toList();
		if (vigentes.isEmpty()) {
			return DiaDeSlots.sin(fecha, MotivoSinSlots.SIN_PROFESIONAL);
		}

		List<Slot> slots = new ArrayList<>();
		MotivoSinSlots motivoDeLaDisponibilidad = null;
		boolean huboFranjas = false;

		for (HabilitacionSnapshot habilitacion : vigentes) {
			DiaDisponible dia = porProfesional
					.getOrDefault(habilitacion.recursoId(), Map.of())
					.get(fecha);
			if (dia == null) {
				continue;
			}
			if (dia.franjas().isEmpty()) {
				// Se guarda el motivo del PRIMER profesional que no atiende, por si al final no
				// atiende ninguno. Si otro si atiende, este motivo se descarta: el dia tiene
				// turnos y no hay nada que explicar.
				if (motivoDeLaDisponibilidad == null) {
					motivoDeLaDisponibilidad = traducir(dia.razonVacio());
				}
				continue;
			}
			huboFranjas = true;
			slots.addAll(cortar(dia, habilitacion.recursoId(), oferta, duracion, zona, fecha));
		}

		if (!slots.isEmpty()) {
			return DiaDeSlots.con(fecha, descontarReservas(slots));
		}
		if (huboFranjas) {
			// Hubo horario y hubo recursos, pero ninguna franja alcanzaba para un slot entero:
			// bloques de 30 minutos con una oferta de 45. Sin este motivo el dia sale vacio
			// "porque si" y el administrador no puede saber que lo unico que le falta es ampliar
			// el bloque.
			return DiaDeSlots.sin(fecha, MotivoSinSlots.FRANJA_MAS_CORTA_QUE_LA_OFERTA);
		}
		return DiaDeSlots.sin(fecha, motivoDeLaDisponibilidad == null
				? MotivoSinSlots.SIN_HORARIO
				: motivoDeLaDisponibilidad);
	}

	/**
	 * Corta las franjas de un profesional en slots.
	 *
	 * <p>Las franjas llegan en instantes UTC —es lo que M05 publica— y el generador trabaja en
	 * hora local, asi que se vuelve a hora local de la sede para cortar y se proyecta de nuevo al
	 * final. Parece una ida y vuelta gratuita y no lo es: cortar en UTC produce slots corridos en
	 * cuanto la sede tenga horario de verano, porque una franja de 09:00 a 17:00 locales dura 7 u
	 * 9 horas de reloj el dia del cambio, y los slots tienen que seguir cayendo en horas de pared
	 * redondas.
	 */
	private static List<Slot> cortar(
			DiaDisponible dia,
			long profesionalId,
			OfertaSnapshot oferta,
			Duration duracion,
			ZoneId zona,
			LocalDate fecha) {

		List<Slot> slots = new ArrayList<>();
		for (DisponibilidadDirectory.Franja franja : dia.franjas()) {
			TramoLocal local = new TramoLocal(
					franja.desde().atZone(zona).toLocalTime(),
					finLocal(franja.hasta(), zona, fecha));

			for (TramoLocal trozo : SlotGenerator.cortar(local, duracion)) {
				slots.add(new Slot(
						trozo.desde(),
						trozo.hasta(),
						profesionalId,
						null,
						oferta.capacidad(),
						oferta.capacidad()));
			}
		}
		return slots;
	}

	/**
	 * Hora local de fin de una franja, tolerando la que termina a medianoche.
	 *
	 * <p>M05 publica el fin de una franja que llega al fin del dia como el <b>inicio del dia
	 * siguiente</b>. Convertido a {@code LocalTime} eso da 00:00, que es ANTERIOR a su inicio y
	 * hace que {@link TramoLocal} lance en el constructor. Se detecta comparando la fecha
	 * resultante con la del dia y se mapea a {@link TramoLocal#FIN_DE_DIA}, que es como el
	 * dominio de M05 representa las 24:00.
	 */
	private static LocalTime finLocal(Instant hasta, ZoneId zona, LocalDate fecha) {
		var zoned = hasta.atZone(zona);
		return zoned.toLocalDate().isAfter(fecha) ? TramoLocal.FIN_DE_DIA : zoned.toLocalTime();
	}

	/**
	 * Descuenta de cada slot las reservas que ya lo tomaron.
	 *
	 * <p><b>Hoy no descuenta nada</b>: {@link ReservaProbe} devuelve el mapa vacio hasta que 05.02
	 * cree la tabla {@code turno}. La llamada existe igual para que el cableado se ejercite desde
	 * el primer dia — un {@code if} que salteara la sonda mientras no haya turnos dejaria este
	 * camino sin correr nunca, y el dia que 05.02 lo active seria la primera vez que se ejecuta.
	 *
	 * <p>Los slots sin lugar <b>no se filtran</b>: viajan con {@code cupoLibre} en cero. La
	 * pantalla necesita poder mostrar "14:00 completo" en vez de un hueco en la grilla, que el
	 * usuario leeria como "no atiende a esa hora".
	 */
	private List<Slot> descontarReservas(List<Slot> slots) {
		return slots;
	}

	/**
	 * {@code true} si al menos un espacio habilitado esta en servicio y es de esta sede.
	 *
	 * <p>El filtro por {@code consultorioId} es redundante —una habilitacion de esta oferta apunta
	 * a un espacio de esta sede— y se deja igual: cuesta una comparacion y cierra el unico camino
	 * por el que un espacio de otra sede podria colarse si alguna vez se afloja el unique de 02.07.
	 */
	private boolean hayEspacio(
			List<HabilitacionSnapshot> habilitados,
			long organizationId,
			long consultorioId,
			Instant at) {

		return habilitados.stream()
				.filter(habilitacion -> habilitacion.vigenteEn(at))
				.map(habilitacion -> espacios.find(organizationId, habilitacion.recursoId(), at))
				.anyMatch(espacio -> espacio
						.filter(EspacioSnapshot::active)
						.filter(EspacioSnapshot::enServicio)
						.filter(candidato -> candidato.consultorioId() == consultorioId)
						.isPresent());
	}

	private static List<HabilitacionSnapshot> filtrarPorProfesional(
			List<HabilitacionSnapshot> habilitados, Long profesionalId) {

		if (profesionalId == null) {
			return habilitados;
		}
		return habilitados.stream()
				.filter(habilitacion -> habilitacion.recursoId() == profesionalId)
				.toList();
	}

	private static Map<LocalDate, DiaDisponible> indexarPorFecha(List<DiaDisponible> dias) {
		Map<LocalDate, DiaDisponible> porFecha = new LinkedHashMap<>();
		for (DiaDisponible dia : dias) {
			porFecha.put(dia.fecha(), dia);
		}
		return porFecha;
	}

	/**
	 * Traduce el {@code razonVacio} de M05 al vocabulario de la agenda.
	 *
	 * <p>{@code null} con franjas vacias significa en M05 "ninguna regla lo abrio", que es un
	 * cuarto estado deliberado suyo. La agenda no puede propagarlo como {@code null} porque de
	 * este lado {@code null} ya significa "el dia TIENE slots": se traduce a
	 * {@link MotivoSinSlots#SIN_HORARIO}.
	 *
	 * <p>El {@code default} cubre un valor que M05 agregue en el futuro sin avisar. Cae en
	 * {@code SIN_HORARIO}, que es impreciso pero cierto —el dia no tiene franjas— y preferible a
	 * lanzar en una lectura por un enum que crecio del otro lado.
	 */
	private static MotivoSinSlots traducir(String razonVacio) {
		if (razonVacio == null) {
			return MotivoSinSlots.SIN_HORARIO;
		}
		return switch (razonVacio) {
			case "FERIADO" -> MotivoSinSlots.FERIADO;
			case "CIERRE" -> MotivoSinSlots.CIERRE;
			case "VINCULO" -> MotivoSinSlots.VINCULO;
			default -> MotivoSinSlots.SIN_HORARIO;
		};
	}

	/**
	 * Proyecta un dia a instantes UTC.
	 *
	 * <p>Los slots se ordenan por instante de inicio y no por profesional: la pantalla dibuja una
	 * grilla horaria, y dos profesionales que atienden en paralelo tienen que aparecer intercalados
	 * por hora. El orden es total y estable —el generador nunca produce dos slots con el mismo
	 * inicio para el mismo profesional— asi que el resultado sigue siendo determinista, que es el
	 * criterio de aceptacion de la etapa.
	 */
	private static DiaDeAgenda proyectar(DiaDeSlots dia, LocalDate fecha, ZoneId zona) {
		List<SlotDisponible> slots = dia.slots().stream()
				.map(slot -> new SlotDisponible(
						fecha.atTime(slot.desde()).atZone(zona).toInstant(),
						finInstante(slot.hasta(), fecha, zona),
						slot.profesionalId(),
						slot.espacioId(),
						slot.cupoTotal(),
						slot.cupoLibre()))
				.sorted(Comparator.comparing(SlotDisponible::desde)
						.thenComparing(slot -> slot.profesionalId() == null ? 0L : slot.profesionalId()))
				.toList();

		return new DiaDeAgenda(fecha, dia.motivo() == null ? null : dia.motivo().name(), slots);
	}

	/**
	 * Ver {@code DisponibilidadEfectivaService#aInstante}: {@link TramoLocal#FIN_DE_DIA} no
	 * admite sumas y se expresa como el inicio del dia siguiente.
	 */
	private static Instant finInstante(LocalTime hora, LocalDate fecha, ZoneId zona) {
		if (TramoLocal.FIN_DE_DIA.equals(hora)) {
			return fecha.plusDays(1).atStartOfDay(zona).toInstant();
		}
		return fecha.atTime(hora).atZone(zona).toInstant();
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * Falta de contexto es <b>403 y nunca 401</b>: el interceptor del frontend borra el token ante
	 * cualquier 401 y dejaria al usuario en un bucle de login del que no sale.
	 */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La consulta de agenda requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * Pertenencia primero y permiso despues: una sede de otro tenant sale por 404 antes de que el
	 * evaluador pueda contestar 403, porque un 403 confirmaria que esa sede existe.
	 */
	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/**
	 * Exige {@code turno:read} con la SEDE como alcance.
	 *
	 * <p>No se exige ademas que la sede de la ruta sea la del contexto activo: la sede viaja como
	 * alcance al evaluador, asi que un {@code CONSULTORIO_ADMIN} de otra sede recibe 403 por la
	 * formula del evaluador. Una segunda regla que duplique la decision solo agregaria un lugar
	 * donde equivocarse. Mismo criterio que {@code DisponibilidadEfectivaService}.
	 */
	private void exigirLectura(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.TURNO_READ,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
