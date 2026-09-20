package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.EventoExternoDeAgenda;
import com.akine.scheduling.spi.EventoExternoDeAgenda.EventoDeAgendaExterno;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * La agenda de una sede con turnos y clases en la misma grilla (RF-M12-011, RF-M12-013).
 *
 * <h2>Que hace, y sobre todo que NO hace</h2>
 *
 * <p>Compone dos listas y las ordena. <b>No unifica la semantica de nada</b>: no hay tabla comun,
 * no hay herencia, no se migra un solo turno y las dos entidades conservan sus reglas y sus
 * comandos. La union es <b>discriminada</b> por {@code tipo}, y las operaciones se delegan al
 * agregado que corresponda, cada una en su propio endpoint. Es literalmente lo que pide
 * RF-M12-013 y lo que el plan llama "no polimorfismo prematuro destructivo".
 *
 * <p>Los turnos los lee este modulo. Las clases llegan por {@link EventoExternoDeAgenda}, un
 * contrato declarado aca e implementado por {@code activity}: <b>{@code scheduling} no importa
 * nada de {@code activity}</b>, y por eso la dependencia entre los dos sigue siendo
 * unidireccional.
 *
 * <h2>Por que es una operacion nueva y no un cambio de la que ya habia</h2>
 *
 * <p>{@code GET /turnos?fecha=} sigue devolviendo exactamente lo que devolvia. Cambiar su respuesta
 * para meterle clases habria sido incompatible —un cliente que espera turnos se encontraria con
 * eventos que no sabe dibujar— y la recepcion tiene casos donde quiere ver solo turnos.
 */
@Service
public class AgendaUnificadaService {

	/** Discriminador de los eventos de M12. */
	public static final String TIPO_TURNO = "TURNO";

	private final TurnoRepositoryPort turnos;
	private final EventoExternoDeAgenda eventosExternos;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;

	public AgendaUnificadaService(
			TurnoRepositoryPort turnos,
			EventoExternoDeAgenda eventosExternos,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard) {

		this.turnos = turnos;
		this.eventosExternos = eventosExternos;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * Todo lo que empieza ese dia local en esa sede, ordenado por hora.
	 *
	 * <p>La fecha se interpreta en la zona de la SEDE y no en la del cliente: "el 5 de octubre" es
	 * un dia local, y resolverlo con la zona del navegador correria la grilla entera <b>sin
	 * fallar</b>, que es peor que fallar.
	 *
	 * <p>Exige {@code turno:read} y no {@code clase:read}: es la agenda, y quien puede ver la
	 * agenda de una sede ve lo que hay en ella. La lista no expone participantes de ninguna clase,
	 * asi que no hay dato de M28 que este permiso no deba cubrir.
	 */
	@Transactional(readOnly = true)
	public AgendaUnificadaView delDia(OperatingActor actor, long consultorioId, LocalDate fecha) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.TURNO_READ, organizationId, consultorioId,
				null, Instant.now()));

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant desde = fecha.atStartOfDay(zona).toInstant();
		Instant hasta = fecha.plusDays(1).atStartOfDay(zona).toInstant();

		List<EventoDeAgendaView> eventos = new ArrayList<>();
		for (Turno turno : turnos.findDeLaSedeEnVentana(organizationId, consultorioId, desde, hasta)) {
			eventos.add(proyectar(organizationId, consultorioId, turno));
		}
		for (EventoDeAgendaExterno externo
				: eventosExternos.enVentana(organizationId, consultorioId, desde, hasta)) {
			eventos.add(proyectar(externo));
		}

		// Por hora y despues por tipo e id: sin el desempate, dos eventos que empiezan al mismo
		// minuto se ordenarian distinto en cada llamada y la grilla bailaria al refrescar.
		eventos.sort(Comparator
				.comparing(EventoDeAgendaView::inicio)
				.thenComparing(EventoDeAgendaView::tipo)
				.thenComparing(EventoDeAgendaView::eventoId));

		return new AgendaUnificadaView(fecha, sede.timezone(), List.copyOf(eventos));
	}

	/**
	 * <p>Un turno individual tiene capacidad 1 y ocupacion 1: es el unico numero coherente con una
	 * grilla donde al lado hay una clase de 0/8. Para un turno de oferta grupal, la capacidad es la
	 * de la oferta — cuantos caben en ese slot— y la ocupacion sigue siendo 1, porque este evento
	 * es <b>esta</b> reserva. Contar los hermanos del slot seria una consulta por fila y una
	 * pregunta que la grilla no hace.
	 */
	private EventoDeAgendaView proyectar(long organizationId, long consultorioId, Turno turno) {
		var oferta = ofertas.find(organizationId, consultorioId, turno.getOfertaId());
		return new EventoDeAgendaView(
				TIPO_TURNO,
				turno.getId(),
				turno.getInicio(),
				turno.getFin(),
				turno.getEstado().name(),
				turno.getOfertaId(),
				oferta.map(o -> o.nombreComercial()).orElse(null),
				null,
				turno.getProfesionalMembershipId(),
				turno.getEspacioId(),
				oferta.map(o -> o.capacidad()).orElse(1),
				1,
				turno.getPersonaId());
	}

	/** El evento externo ya viene proyectado por su modulo dueno: aca solo cambia de forma. */
	private static EventoDeAgendaView proyectar(EventoDeAgendaExterno externo) {
		return new EventoDeAgendaView(
				externo.tipo(),
				externo.eventoId(),
				externo.inicio(),
				externo.fin(),
				externo.estado(),
				externo.ofertaId(),
				externo.ofertaNombre(),
				externo.titulo(),
				externo.profesionalId(),
				externo.espacioId(),
				externo.capacidad(),
				externo.ocupados(),
				null);
	}

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La agenda requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}
}
