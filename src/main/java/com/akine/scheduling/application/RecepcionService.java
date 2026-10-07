package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * La agenda del dia de una sede (M13, AKINE-05.04 reducida).
 *
 * <h2>La pregunta que contesta, y que hasta ahora no se podia hacer</h2>
 *
 * <p>Un centro abre el dia preguntando <b>quien viene hoy</b>. Antes de esta etapa no habia forma
 * de contestarlo: la unica lectura de agenda era {@code AgendaService}, que devuelve <b>slots
 * libres de UNA oferta</b> —lo que necesita quien va a reservar, no quien va a recibir—. Tampoco
 * existia la lectura de un turno solo, y por eso la pantalla del ciclo de vida tuvo que
 * reconstruir el estado desde el historial.
 *
 * <h2>Por que es una clase aparte de CicloDeTurnoService</h2>
 *
 * <p>Esto es <b>solo lectura</b> y aquello es la maquina de estados. Mezclarlas haria que la clase
 * que decide si un turno se puede cancelar cargue tambien con resolver nombres de pacientes y
 * nombres comerciales de ofertas, que es trabajo de presentacion. Las transiciones de recepcion
 * viven en {@link CicloDeRecepcionService} desde E-4 (DP-16), con su maquina de estados propia.
 *
 * <h2>El dia se calcula en la zona de la SEDE</h2>
 *
 * <p>Un turno se guarda como instante UTC, pero "el 7 de septiembre" es una fecha local. Convertir
 * con la zona del servidor —o peor, con la del navegador de la recepcionista— haria que la agenda
 * empiece y termine en horas distintas segun quien mire. La zona sale de
 * {@code ConsultorioSnapshot}, que es la misma que usa el motor de slots.
 *
 * <h2>PHI minima</h2>
 *
 * <p>La respuesta lleva nombre, documento y el nombre comercial de la oferta. <b>Nada clinico.</b>
 * Quien atiende el mostrador no necesita saber por que viene el paciente para decirle que pase, y
 * el plan lo pide explicito.
 */
@Service
public class RecepcionService {

	private final TurnoRepositoryPort turnos;
	private final RecepcionRepositoryPort recepciones;
	private final ConsultorioDirectory consultorios;
	private final PacienteDirectory pacientes;
	private final OfertaDirectory ofertas;
	private final PermissionGuard permissionGuard;

	public RecepcionService(
			TurnoRepositoryPort turnos,
			RecepcionRepositoryPort recepciones,
			ConsultorioDirectory consultorios,
			PacienteDirectory pacientes,
			OfertaDirectory ofertas,
			PermissionGuard permissionGuard) {

		this.turnos = turnos;
		this.recepciones = recepciones;
		this.consultorios = consultorios;
		this.pacientes = pacientes;
		this.ofertas = ofertas;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * Los turnos de una sede en un dia, del mas temprano al mas tarde.
	 *
	 * <p><b>Incluye los cancelados.</b> No es un descuido: alguien puede presentarse en el mostrador
	 * a un turno que se cancelo, y una lista que los esconda deja a la recepcionista sin nada que
	 * decirle. Vienen con su motivo, que es lo que hace util verlos.
	 *
	 * <p>Exige {@code turno:read} y no {@code turno:manage}: mirar quien viene hoy es leer la
	 * agenda, no operarla.
	 *
	 * @throws ConsultorioNoAccesibleException la sede no existe o es de otro tenant (404)
	 */
	@Transactional(readOnly = true)
	public AgendaDelDiaView delDia(OperatingActor actor, long consultorioId, LocalDate fecha) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.TURNO_READ,
				organizationId, consultorioId, null, Instant.now()));

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant desde = fecha.atStartOfDay(zona).toInstant();
		Instant hasta = fecha.plusDays(1).atStartOfDay(zona).toInstant();

		List<Turno> delDia = turnos.findDeLaSedeEnVentana(
				organizationId, consultorioId, desde, hasta);

		// La zona viaja SIEMPRE, tambien en un dia vacio. Es un dato de la sede y no de los
		// turnos: si solo se enviara cuando hay alguno, la pantalla tendria que deducirla de
		// otro lado justo el dia en que no hay nada de donde deducirla.
		return new AgendaDelDiaView(
				fecha,
				sede.timezone(),
				delDia.isEmpty() ? List.of() : componer(organizationId, consultorioId, delDia));
	}

	/**
	 * Un turno solo, con el paciente y la oferta ya resueltos.
	 *
	 * <p>Existe porque <b>no habia ninguna forma de leer un turno</b>: el contrato solo publicaba
	 * la agenda de slots libres y el historial de transiciones, asi que una pantalla que llegara
	 * por un enlace directo tenia que deducir el estado del turno a partir de sus eventos. Eso es
	 * fragil y ademas obliga a leer todo el historial para mostrar una linea.
	 *
	 * @throws TurnoNotAccessibleException el turno no existe en esa sede (404)
	 */
	@Transactional(readOnly = true)
	public TurnoDelDiaView ver(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.TURNO_READ,
				organizationId, consultorioId, null, Instant.now()));

		Turno turno = turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));

		return componer(organizationId, consultorioId, List.of(turno)).get(0);
	}

	/**
	 * Resuelve nombres para un lote de turnos.
	 *
	 * <p><b>Dos consultas y no dos por turno.</b> Los pacientes se piden todos juntos —para eso
	 * nacio {@code PacienteDirectory.findAll}— y las ofertas se cachean por id dentro de la
	 * llamada: un dia de agenda suele repetir la misma oferta decenas de veces, y pedirla una vez
	 * por turno seria N consultas para N respuestas identicas.
	 */
	private List<TurnoDelDiaView> componer(
			long organizationId, long consultorioId, List<Turno> lote) {

		Map<Long, PacienteSnapshot> personas = pacientes.findAll(
				organizationId, lote.stream().map(Turno::getPersonaId).toList());

		// La recepcion vigente de cada turno, en una sola consulta (E-4). Desde DP-16 la llegada no
		// esta en el turno: sin esto la agenda del dia no sabria quien ya llego.
		Map<Long, Recepcion> recepcionDe = recepciones
				.findVigentesDeTurnos(organizationId, lote.stream().map(Turno::getId).toList())
				.stream()
				.collect(Collectors.toMap(Recepcion::getTurnoId, Function.identity()));

		Map<Long, String> nombreDeOferta = new HashMap<>();
		return lote.stream()
				.map(turno -> new TurnoDelDiaView(
						turno.getId(),
						turno.getInicio(),
						turno.getFin(),
						turno.getEstado().name(),
						turno.getPersonaId(),
						nombreDe(personas.get(turno.getPersonaId())),
						documentoDe(personas.get(turno.getPersonaId())),
						turno.getOfertaId(),
						nombreDeOferta.computeIfAbsent(
								turno.getOfertaId(),
								ofertaId -> nombreComercialDe(organizationId, consultorioId, ofertaId)),
						turno.getProfesionalMembershipId(),
						turno.getEspacioId(),
						llegadaDe(recepcionDe.get(turno.getId())),
						turno.getMotivoCancelacion(),
						turno.getSerieId(),
						turno.getVersion(),
						recepcionDe.containsKey(turno.getId())
								? RecepcionView.de(recepcionDe.get(turno.getId()))
								: null))
				.toList();
	}

	private static Instant llegadaDe(Recepcion recepcion) {
		return recepcion == null ? null : recepcion.getLlegadaEn();
	}

	/**
	 * El nombre del paciente, o un marcador si la ficha ya no esta.
	 *
	 * <p>Una persona dada de baja despues de reservar deja su turno en pie —el turno es un hecho de
	 * la agenda— y la lista tiene que poder mostrarlo igual. Devolver {@code null} obligaria a
	 * cada consumidor a decidir que poner; devolver la lista sin ese turno escondería una fila que
	 * ocupa un lugar real en la agenda del profesional.
	 */
	private static String nombreDe(PacienteSnapshot paciente) {
		return paciente == null ? "(ficha no disponible)" : paciente.nombreCompleto();
	}

	private static String documentoDe(PacienteSnapshot paciente) {
		if (paciente == null || paciente.numeroDocumento() == null) {
			return null;
		}
		return paciente.tipoDocumento() == null
				? paciente.numeroDocumento()
				: paciente.tipoDocumento() + " " + paciente.numeroDocumento();
	}

	private String nombreComercialDe(long organizationId, long consultorioId, long ofertaId) {
		return ofertas.find(organizationId, consultorioId, ofertaId)
				.map(OfertaSnapshot::nombreComercial)
				.orElse("(oferta no disponible)");
	}

	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			// 403 y no 401: la credencial es valida, lo que falta es el contexto de trabajo.
			throw new AccessDeniedException("Se requiere contexto de organizacion");
		}
		return organizationId;
	}

	private ConsultorioSnapshot exigirSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}
}
