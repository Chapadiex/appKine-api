package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.scheduling.application.DisponibilidadView.HorarioDisponibleView;
import com.akine.scheduling.application.PrevisualizacionDeSerieView.OcurrenciaPrevistaView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Previsualiza una programacion multiple (TURNO.md §5 y §13, TURNO - Tecnico §20).
 *
 * <p><b>No mezcla preview y creacion</b> (TURNO - Tecnico §45). Evalua cada ocurrencia con los mismos
 * controles de la reserva y, si una no se puede, busca la alternativa mas cercana. Una alternativa
 * sugerida puede pisarse con otra ocurrencia de la misma serie o con lo que entre mientras tanto: lo
 * decide el alta, bajo el lock de la sede, y en ese caso rechaza todo y nombra la ocurrencia.
 */
@Service
public class PrevisualizacionDeSerieService {

	private final RevalidadorDeSlot revalidador;
	private final DisponibilidadDeTurnosService disponibilidad;
	private final ConsultorioDirectory consultorios;
	private final OfertaDirectory ofertas;
	private final NombresDeAgenda nombres;
	private final ContextoClinicoDeTurno contextoClinico;
	private final SesionesDelPlan sesionesDelPlan;
	private final SoporteEnAgenda soporte;

	public PrevisualizacionDeSerieService(
			RevalidadorDeSlot revalidador,
			DisponibilidadDeTurnosService disponibilidad,
			ConsultorioDirectory consultorios,
			OfertaDirectory ofertas,
			NombresDeAgenda nombres,
			ContextoClinicoDeTurno contextoClinico,
			SesionesDelPlan sesionesDelPlan,
			SoporteEnAgenda soporte) {

		this.revalidador = revalidador;
		this.disponibilidad = disponibilidad;
		this.consultorios = consultorios;
		this.ofertas = ofertas;
		this.nombres = nombres;
		this.contextoClinico = contextoClinico;
		this.sesionesDelPlan = sesionesDelPlan;
		this.soporte = soporte;
	}

	/** Sin {@code @Transactional}: cada lectura ve lo ultimo commiteado, y nada de esto escribe. */
	public PrevisualizacionDeSerieView previsualizar(
			OperatingActor actor, long consultorioId, AltaDeSerieCommand command) {

		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La agenda requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		ConsultorioSnapshot sede = consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, command.ofertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(command.ofertaId()));

		ContextoClinicoDeTurno.Resuelto contexto = contextoClinico.resolver(
				organizationId, command.personaId(), command.ofertaId(), command.casoId(), command.planId());
		PlanAgendable plan = contexto.plan();
		Integer pendientes = null;
		if (plan != null) {
			var item = sesionesDelPlan.resumir(organizationId, plan).item(command.ofertaId());
			pendientes = item == null ? 0 : item.pendientes();
		}

		ZoneId zona = ZoneId.of(sede.timezone());
		List<LocalDateTime> locales = command.regla().ocurrencias();
		Set<LocalDate> feriados = revalidador.feriadosQueCierran(
				organizationId, sede, oferta, command.profesionalId(),
				locales.getFirst().toLocalDate(), locales.getLast().toLocalDate().plusDays(1));
		Instant ahora = Instant.now();
		int duracion = oferta.duracionCon(
				ofertas.duracionesPropias(organizationId, consultorioId, command.ofertaId()), command.profesionalId());

		List<OcurrenciaPrevistaView> previstas = new ArrayList<>();
		int numero = 0;
		for (LocalDateTime local : locales) {
			numero++;
			Instant inicio = local.atZone(zona).toInstant();
			Instant fin = inicio.plusSeconds(duracion * 60L);
			String estado = "DISPONIBLE";
			String motivo = null;
			Long espacio = null;
			if (feriados.contains(local.toLocalDate())) {
				estado = "FERIADO";
				motivo = "La sede cierra por feriado.";
			} else if (!inicio.isAfter(ahora)) {
				estado = "NO_DISPONIBLE";
				motivo = "Ese horario ya paso.";
			} else if (!oferta.vigenteEl(local.toLocalDate())) {
				estado = "NO_DISPONIBLE";
				motivo = "La prestacion no esta vigente ese dia.";
			} else {
				try {
					espacio = revalidador.evaluarSinLock(new RevalidadorDeSlot.Pedido(
							organizationId, consultorioId, sede, oferta, inicio, fin,
							command.profesionalId(), null)).espacioId();
				} catch (RecursoOcupadoException ocupado) {
					estado = "NO_DISPONIBLE";
					motivo = "profesional".equals(ocupado.getRecurso())
							? "El profesional ya tiene otro turno en ese horario."
							: "No queda ningun box libre en ese horario.";
				} catch (SlotCompletoException completo) {
					estado = "NO_DISPONIBLE";
					motivo = "Ese horario ya no tiene lugar.";
				} catch (SlotNoDisponibleException sinHueco) {
					estado = "NO_DISPONIBLE";
					motivo = sinHueco.getMotivo();
				}
			}
			HorarioDisponibleView sugerencia = "DISPONIBLE".equals(estado)
					? null
					: sugerir(actor, consultorioId, command, local.toLocalDate(), inicio);
			previstas.add(new OcurrenciaPrevistaView(
					numero, inicio, fin, command.profesionalId(), null, espacioNombre(organizationId, espacio, inicio),
					estado, motivo, sugerencia));
		}

		Map<Long, String> nombresDe = nombres.profesionales(organizationId,
				previstas.stream().map(OcurrenciaPrevistaView::profesionalId)
						.filter(Objects::nonNull).collect(Collectors.toSet()));
		List<OcurrenciaPrevistaView> conNombre = previstas.stream()
				.map(prevista -> new OcurrenciaPrevistaView(
						prevista.numero(), prevista.inicio(), prevista.fin(), prevista.profesionalId(),
						prevista.profesionalId() == null ? null : nombresDe.get(prevista.profesionalId()),
						prevista.espacioNombre(), prevista.estado(), prevista.motivo(), prevista.sugerencia()))
				.toList();

		return new PrevisualizacionDeSerieView(oferta.id(), oferta.nombreComercial(), duracion,
				sede.timezone(), pendientes, conNombre);
	}

	private String espacioNombre(long organizationId, Long espacioId, Instant at) {
		return espacioId == null ? null : nombres.espacios(organizationId, List.of(espacioId), at).get(espacioId);
	}

	/**
	 * El primer horario libre desde ese momento, con el mismo profesional. Un dia completo mira adelante.
	 *
	 * <p>Una serie puede cruzar el fin de la vigencia de la oferta: esas ocurrencias se muestran vencidas
	 * y sin sugerencia. La busqueda rechaza una ventana que la oferta no toca, y ese rechazo no puede
	 * tirar abajo la revision entera de una serie cuyas primeras ocurrencias se pueden.
	 */
	private HorarioDisponibleView sugerir(
			OperatingActor actor, long consultorioId, AltaDeSerieCommand command, LocalDate fecha, Instant desde) {

		try {
			return disponibilidad.buscar(actor, consultorioId, new BusquedaDeDisponibilidad(
							command.ofertaId(), command.profesionalId(), fecha, fecha.plusDays(14),
							null, null, null, 24))
					.horarios().stream()
					.filter(horario -> horario.desde().isAfter(desde))
					.findFirst()
					.orElse(null);
		} catch (OfertaNoAgendableException sinNadaQueSugerir) {
			return null;
		}
	}
}
