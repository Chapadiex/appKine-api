package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.application.DisponibilidadView.HorarioDisponibleView;
import com.akine.scheduling.application.ValidacionDeHorarioView.RecursoLibreView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * El motor de disponibilidad que usan TODOS los puntos de entrada (TURNO - Tecnico §12 y §16).
 *
 * <p><b>No calcula nada propio.</b> Los horarios salen de {@link AgendaService} y la validacion de
 * {@link RevalidadorDeSlot}, que son las mismas reglas que aplica la reserva: horario del profesional,
 * sede, feriados, cierres, licencias, habilitaciones, box, clases y turnos existentes. Esta clase
 * solo acerca esas respuestas a lo que el operador pregunta: "cualquier profesional", "mañana a la
 * tarde", "el primero que haya". Si tuviera reglas propias, lo que ofrece y lo que la reserva acepta
 * se separarian sin que nada falle.
 *
 * <p><b>Nada de esto reserva ni bloquea</b> (TURNO - Tecnico §66). La disponibilidad que se muestra es
 * informativa; la decision es de la reserva, bajo el lock de la sede.
 */
@Service
public class DisponibilidadDeTurnosService {

	private static final int LIMITE_POR_DEFECTO = 20;
	private static final int LIMITE_MAXIMO = 200;
	/** Cuantos dias se piden por vuelta: corta temprano sin pagar la ventana entera. */
	private static final int DIAS_POR_TANDA = 14;
	private static final int ALTERNATIVAS = 3;
	private static final String ROL_PROFESIONAL = "PROFESIONAL";

	private final AgendaService agenda;
	private final RevalidadorDeSlot revalidador;
	private final ConsultorioDirectory consultorios;
	private final OfertaDirectory ofertas;
	private final ConsultorioMembershipDirectory memberships;
	private final NombresDeAgenda nombres;
	private final ContextoClinicoDeTurno contextoClinico;
	private final ContextoClinicoDeAgenda contextoDeAgenda;
	private final SesionesDelPlan sesionesDelPlan;
	private final SoporteEnAgenda soporte;

	public DisponibilidadDeTurnosService(
			AgendaService agenda,
			RevalidadorDeSlot revalidador,
			ConsultorioDirectory consultorios,
			OfertaDirectory ofertas,
			ConsultorioMembershipDirectory memberships,
			NombresDeAgenda nombres,
			ContextoClinicoDeTurno contextoClinico,
			ContextoClinicoDeAgenda contextoDeAgenda,
			SesionesDelPlan sesionesDelPlan,
			SoporteEnAgenda soporte) {

		this.agenda = agenda;
		this.revalidador = revalidador;
		this.consultorios = consultorios;
		this.ofertas = ofertas;
		this.memberships = memberships;
		this.nombres = nombres;
		this.contextoClinico = contextoClinico;
		this.contextoDeAgenda = contextoDeAgenda;
		this.sesionesDelPlan = sesionesDelPlan;
		this.soporte = soporte;
	}

	/**
	 * Los proximos horarios libres. Sin {@code hasta} sigue buscando hasta llenar el limite o agotar los
	 * {@value VentanaDeAgenda#VENTANA_MAXIMA_DIAS} dias de ventana, de a tandas para no leer de mas.
	 *
	 * @throws OfertaNotAccessibleException la oferta no existe en esa sede (404)
	 * @throws OfertaNoAgendableException   la oferta no puede agendar en ningun dia de la ventana (409)
	 */
	public DisponibilidadView buscar(
			OperatingActor actor, long consultorioId, BusquedaDeDisponibilidad pedido) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, pedido.ofertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(pedido.ofertaId()));

		ZoneId zona = ZoneId.of(sede.timezone());
		LocalDate desde = pedido.desde() != null ? pedido.desde() : LocalDate.now(zona);
		LocalDate hasta = pedido.hasta() != null
				? pedido.hasta()
				: desde.plusDays(VentanaDeAgenda.VENTANA_MAXIMA_DIAS);
		VentanaDeAgenda.exigirValida(desde, hasta);
		if (oferta.noAgendableEn(desde, hasta)) {
			throw new OfertaNoAgendableException(pedido.ofertaId(),
					oferta.active() ? "su vigencia no toca la ventana pedida" : "esta dada de baja");
		}
		int limite = Math.min(pedido.limite() == null ? LIMITE_POR_DEFECTO : pedido.limite(), LIMITE_MAXIMO);

		List<AgendaView.SlotDisponible> elegidos = new ArrayList<>();
		Set<String> motivos = new LinkedHashSet<>();
		boolean truncado = false;
		LocalDate tanda = desde;
		while (tanda.isBefore(hasta) && !truncado) {
			LocalDate finDeTanda = tanda.plusDays(DIAS_POR_TANDA).isAfter(hasta) ? hasta : tanda.plusDays(DIAS_POR_TANDA);
			if (!oferta.noAgendableEn(tanda, finDeTanda)) {
				AgendaView vista = agenda.buscar(
						actor, consultorioId, pedido.ofertaId(), tanda, finDeTanda, pedido.profesionalId());
				for (AgendaView.DiaDeAgenda dia : vista.dias()) {
					if (dia.motivoSinSlots() != null) {
						motivos.add(dia.motivoSinSlots());
					}
					if (pedido.dias() != null && !pedido.dias().isEmpty()
							&& !pedido.dias().contains(dia.fecha().getDayOfWeek())) {
						continue;
					}
					for (AgendaView.SlotDisponible slot : dia.slots()) {
						if (slot.cupoLibre() > 0 && dentroDeLaFranja(slot, pedido, zona)) {
							if (elegidos.size() == limite) {
								truncado = true;
								break;
							}
							elegidos.add(slot);
						}
					}
				}
			}
			tanda = finDeTanda;
		}

		return new DisponibilidadView(
				oferta.id(),
				oferta.nombreComercial(),
				oferta.duracionCon(
						pedido.profesionalId() == null
								? Map.of()
								: ofertas.duracionesPropias(organizationId, consultorioId, oferta.id()),
						pedido.profesionalId()),
				oferta.requiereEspacio(),
				sede.timezone(),
				tanda,
				truncado,
				List.copyOf(motivos),
				conNombres(organizationId, elegidos));
	}

	private static boolean dentroDeLaFranja(
			AgendaView.SlotDisponible slot, BusquedaDeDisponibilidad pedido, ZoneId zona) {

		if (pedido.horaDesde() != null
				&& slot.desde().atZone(zona).toLocalTime().isBefore(pedido.horaDesde())) {
			return false;
		}
		// Por el inicio y no por el fin: si no, un turno de 12:45 a 13:30 no estaria ni a la mañana ni a la
		// tarde, y sólo se veria sin filtrar.
		return pedido.horaHasta() == null
				|| slot.desde().atZone(zona).toLocalTime().isBefore(pedido.horaHasta());
	}

	private List<HorarioDisponibleView> conNombres(
			long organizationId, List<AgendaView.SlotDisponible> slots) {

		Map<Long, String> profesionales = nombres.profesionales(organizationId,
				slots.stream().map(AgendaView.SlotDisponible::profesionalId)
						.filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, String> espacios = slots.isEmpty() ? Map.of() : nombres.espacios(organizationId,
				slots.stream().map(AgendaView.SlotDisponible::espacioId)
						.filter(Objects::nonNull).collect(Collectors.toSet()),
				slots.getFirst().desde());
		return slots.stream()
				.map(slot -> new HorarioDisponibleView(
						slot.desde(), slot.hasta(),
						slot.profesionalId(),
						slot.profesionalId() == null ? null : profesionales.get(slot.profesionalId()),
						slot.espacioId(),
						slot.espacioId() == null ? null : espacios.get(slot.espacioId()),
						slot.cupoLibre()))
				.toList();
	}

	/** Lo que el formulario pregunta antes de confirmar. Ver {@link ValidacionDeHorarioView}. */
	@Transactional(readOnly = true)
	public ValidacionDeHorarioView validar(
			OperatingActor actor, long consultorioId, ValidacionDeHorarioCommand pedido) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, pedido.ofertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(pedido.ofertaId()));

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant inicio = pedido.inicio();
		Map<Long, Integer> duracionesPropias = oferta.requiereProfesional()
				? ofertas.duracionesPropias(organizationId, consultorioId, oferta.id())
				: Map.of();
		// Con "cualquier profesional" todavia no hay quien atienda: este fin es el de la oferta, y mas
		// abajo cada candidato se evalua con el suyo.
		Instant fin = finDelTurno(oferta, duracionesPropias, inicio, pedido.profesionalId());
		LocalDate fecha = inicio.atZone(zona).toLocalDate();

		if (!inicio.isAfter(Instant.now())) {
			return noDisponible(actor, consultorioId, pedido, inicio, fin, "PASADO", "Ese horario ya paso.", fecha, null);
		}
		if (!oferta.vigenteEl(fecha)) {
			return noDisponible(actor, consultorioId, pedido, inicio, fin, "OFERTA_NO_AGENDABLE",
					oferta.active() ? "La prestacion no esta vigente ese dia." : "La prestacion esta dada de baja.",
					fecha, null);
		}

		List<String> avisos = new ArrayList<>();
		if (pedido.personaId() != null && (pedido.casoId() != null || pedido.planId() != null)) {
			try {
				ContextoClinicoDeTurno.Resuelto contexto = contextoClinico.resolver(
						organizationId, pedido.personaId(), pedido.ofertaId(), pedido.casoId(), pedido.planId());
				if (contexto.plan() != null) {
					sesionesDelPlan.exigirDisponibles(organizationId, contexto.plan(), pedido.ofertaId(), 1);
				}
				avisoDeResponsable(organizationId, contexto, pedido.profesionalId()).ifPresent(avisos::add);
			} catch (ContextoClinicoInvalidoException | com.akine.scheduling.domain.exception.PlanSinSesionesException rechazo) {
				return noDisponible(actor, consultorioId, pedido, inicio, fin, "CONTEXTO_CLINICO",
						rechazo.getMessage(), fecha, null);
			}
		}

		List<Long> candidatos = pedido.profesionalId() != null || !oferta.requiereProfesional()
				? java.util.Collections.singletonList(pedido.profesionalId())
				: profesionalesDeLaOferta(organizationId, consultorioId, oferta.id(), inicio);

		RuntimeException primerRechazo = null;
		List<Long> libres = new ArrayList<>();
		RevalidadorDeSlot.Pedido paraEspacios = null;
		for (Long candidato : candidatos) {
			RevalidadorDeSlot.Pedido evaluado = new RevalidadorDeSlot.Pedido(
					organizationId, consultorioId, sede, oferta, inicio,
					finDelTurno(oferta, duracionesPropias, inicio, candidato), candidato,
					pedido.turnoExcluidoId(), pedido.espacioId(), false);
			try {
				revalidador.evaluarSinLock(evaluado);
				libres.add(candidato);
				paraEspacios = paraEspacios == null ? evaluado : paraEspacios;
			} catch (SlotNoDisponibleException | SlotCompletoException | RecursoOcupadoException
					| com.akine.scheduling.domain.exception.EspacioNoElegibleException rechazo) {
				primerRechazo = primerRechazo == null ? rechazo : primerRechazo;
			}
		}

		if (libres.isEmpty()) {
			return noDisponibleDesde(actor, consultorioId, pedido, inicio, fin, fecha, primerRechazo);
		}

		Map<Long, String> nombresDeProfesionales = nombres.profesionales(
				organizationId, libres.stream().filter(Objects::nonNull).collect(Collectors.toSet()));
		List<RecursoLibreView> profesionales = libres.stream()
				.filter(Objects::nonNull)
				.map(id -> new RecursoLibreView(
						id, nombresDeProfesionales.getOrDefault(id, "#" + id), duracionesPropias.get(id)))
				.toList();
		List<RecursoLibreView> espacios = revalidador.espaciosLibres(paraEspacios).stream()
				.map((EspacioSnapshot espacio) -> new RecursoLibreView(espacio.id(), espacio.name()))
				.toList();

		// Con uno solo libre, ese es quien atiende. Con varios, el fin de cada uno sale de su duracion
		// propia: ninguno de ellos es "el" fin hasta que se elija.
		Instant finAlResponder = libres.size() == 1
				? finDelTurno(oferta, duracionesPropias, inicio, libres.getFirst())
				: fin;
		return new ValidacionDeHorarioView(
				avisos.isEmpty() ? "DISPONIBLE" : "DISPONIBLE_CON_AVISO",
				null, null, inicio, finAlResponder, profesionales, espacios, null, null, null, avisos, List.of());
	}

	private static Instant finDelTurno(
			OfertaSnapshot oferta, Map<Long, Integer> duracionesPropias, Instant inicio, Long profesionalId) {
		return inicio.plusSeconds(oferta.duracionCon(duracionesPropias, profesionalId) * 60L);
	}

	/**
	 * Cambiar el profesional responsable no se prohibe: se avisa (TURNO.md §15). Bloquearlo dejaria sin
	 * turno al paciente el dia que el responsable no esta, que es justamente cuando se cambia.
	 */
	private java.util.Optional<String> avisoDeResponsable(
			long organizationId, ContextoClinicoDeTurno.Resuelto contexto, Long profesionalId) {

		if (contexto.casoId() == null || profesionalId == null) {
			return java.util.Optional.empty();
		}
		return contextoDeAgenda.caso(organizationId, contexto.casoId())
				.map(ContextoClinicoDeAgenda.CasoAgendable::responsableMembershipId)
				.filter(responsable -> !responsable.equals(profesionalId))
				.map(responsable -> "El turno queda con un profesional distinto del responsable del caso.");
	}

	private ValidacionDeHorarioView noDisponibleDesde(
			OperatingActor actor, long consultorioId, ValidacionDeHorarioCommand pedido,
			Instant inicio, Instant fin, LocalDate fecha, RuntimeException rechazo) {

		if (rechazo instanceof RecursoOcupadoException ocupado) {
			return noDisponible(actor, consultorioId, pedido, inicio, fin,
					"profesional".equals(ocupado.getRecurso()) ? "PROFESIONAL_OCUPADO" : "ESPACIO_OCUPADO",
					"profesional".equals(ocupado.getRecurso())
							? "El profesional ya tiene otro turno en ese horario."
							: "No queda ningun box libre en ese horario.",
					fecha, ocupado);
		}
		if (rechazo instanceof SlotCompletoException) {
			return noDisponible(actor, consultorioId, pedido, inicio, fin, "SLOT_COMPLETO",
					"Ese horario ya no tiene lugar.", fecha, null);
		}
		String motivo = rechazo instanceof SlotNoDisponibleException sinHueco
				? sinHueco.getMotivo()
				: "Ese horario no se puede reservar.";
		return noDisponible(actor, consultorioId, pedido, inicio, fin, "SLOT_NO_DISPONIBLE", motivo, fecha, null);
	}

	private ValidacionDeHorarioView noDisponible(
			OperatingActor actor, long consultorioId, ValidacionDeHorarioCommand pedido,
			Instant inicio, Instant fin, String causa, String motivo, LocalDate fecha,
			RecursoOcupadoException ocupado) {

		List<HorarioDisponibleView> alternativas = "CONTEXTO_CLINICO".equals(causa)
				? List.of()
				: alternativas(actor, consultorioId, pedido, fecha);
		return new ValidacionDeHorarioView(
				"NO_DISPONIBLE", causa, motivo, inicio, fin, List.of(), List.of(),
				ocupado == null ? null : ocupado.getTurnoEnConflictoId(),
				ocupado == null ? null : ocupado.getConflictoInicio(),
				ocupado == null ? null : ocupado.getConflictoFin(),
				List.of(), alternativas);
	}

	/**
	 * Los horarios mas cercanos, con el mismo profesional.
	 *
	 * <p>Una oferta de baja, o que ya vencio, no tiene nada que ofrecer. {@link #buscar} lo rechaza con
	 * 409 porque ahi es la respuesta; aca la respuesta es el 200 con {@code NO_DISPONIBLE} que ya se
	 * armo y el contrato no publica otra cosa: sin esto, preguntar por una oferta de baja se caia al
	 * buscar las alternativas.
	 */
	private List<HorarioDisponibleView> alternativas(
			OperatingActor actor, long consultorioId, ValidacionDeHorarioCommand pedido, LocalDate fecha) {

		try {
			return buscar(actor, consultorioId, new BusquedaDeDisponibilidad(
					pedido.ofertaId(), pedido.profesionalId(), fecha, null, null, null, null, ALTERNATIVAS))
					.horarios();
		} catch (OfertaNoAgendableException sinNadaQueOfrecer) {
			return List.of();
		}
	}

	/**
	 * Quien puede atender una oferta en esta sede, o todos los profesionales de la sede si no se pide
	 * ninguna. <b>Lista de habilitaciones vacia significa todos</b>, igual que en la agenda.
	 */
	@Transactional(readOnly = true)
	public List<RecursoLibreView> profesionales(OperatingActor actor, long consultorioId, Long ofertaId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		if (ofertaId != null) {
			ofertas.find(organizationId, consultorioId, ofertaId)
					.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
		}
		List<Long> ids = ofertaId == null
				? todosLosProfesionales(organizationId, consultorioId)
				: profesionalesDeLaOferta(organizationId, consultorioId, ofertaId, Instant.now());
		Map<Long, String> nombresDe = nombres.profesionales(organizationId, ids);
		Map<Long, Integer> duracionesPropias = ofertaId == null
				? Map.of()
				: ofertas.duracionesPropias(organizationId, consultorioId, ofertaId);
		return ids.stream()
				.map(id -> new RecursoLibreView(id, nombresDe.getOrDefault(id, "#" + id), duracionesPropias.get(id)))
				.sorted(Comparator.comparing(RecursoLibreView::nombre))
				.toList();
	}

	/**
	 * @param at cuando tiene que estar vigente la habilitacion. Al validar un horario es ese horario, y
	 *           no ahora: es lo que miran la agenda y el revalidador, y con ahora una habilitacion que
	 *           arranca la semana que viene dejaba sin nadie a un horario que la busqueda ofrecia
	 */
	private List<Long> profesionalesDeLaOferta(long organizationId, long consultorioId, long ofertaId, Instant at) {
		List<HabilitacionSnapshot> habilitados =
				ofertas.profesionalesHabilitados(organizationId, consultorioId, ofertaId);
		if (habilitados.isEmpty()) {
			return todosLosProfesionales(organizationId, consultorioId);
		}
		return habilitados.stream()
				.filter(habilitacion -> habilitacion.vigenteEn(at))
				.map(HabilitacionSnapshot::recursoId)
				.distinct()
				.toList();
	}

	private List<Long> todosLosProfesionales(long organizationId, long consultorioId) {
		return memberships.findPorRolEnSede(organizationId, consultorioId, ROL_PROFESIONAL).stream()
				.map(membership -> membership.membershipId())
				.toList();
	}

	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La disponibilidad requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}
}
