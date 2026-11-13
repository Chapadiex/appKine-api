package com.akine.scheduling.application;

import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cadena de traslados y el indicador "reprogramado" de los turnos (AKINE-agenda-historial).
 *
 * <p>Un traslado de sede cancela el turno y reserva otro con {@code turno_origen_id} apuntando al
 * original (agenda operativa, D1 bis). Para la agenda, el turno nuevo <b>hereda</b> la historia del
 * original: las reprogramaciones de cualquier eslabon cuentan, y cada traslado es una mas.
 *
 * <p>Todo en lote: una consulta por nivel de la cadena (casi siempre ninguna) y una para los eventos,
 * sea cual sea el tamano del lote. Nada por turno.
 */
@Component
public class ReprogramacionesDeTurnos {

	static final String REPROGRAMACION = "REPROGRAMACION";
	static final String TRASLADO = "TRASLADO";

	/** Tope defensivo de la cadena: un ciclo en los datos no puede colgar una lectura. */
	static final int MAXIMO_DE_SALTOS = 20;

	private final TurnoRepositoryPort turnos;
	private final TurnoEventoRepositoryPort eventos;
	private final AccountIdentityDirectory identidades;
	private final PermissionEvaluator permisos;

	public ReprogramacionesDeTurnos(
			TurnoRepositoryPort turnos,
			TurnoEventoRepositoryPort eventos,
			AccountIdentityDirectory identidades,
			PermissionEvaluator permisos) {
		this.turnos = turnos;
		this.eventos = eventos;
		this.identidades = identidades;
		this.permisos = permisos;
	}

	/** El indicador de cada turno del lote que se reprogramo o se traslado; los demas no aparecen. */
	Map<Long, ReprogramacionDeTurnoView> de(long organizationId, List<Turno> lote) {
		if (lote.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<Turno>> cadenas = cadenas(organizationId, lote);
		Set<Long> ids = cadenas.values().stream().flatMap(List::stream).map(Turno::getId)
				.collect(Collectors.toSet());
		Map<Long, List<TurnoEvento>> porTurno = eventos.reprogramacionesYReservas(organizationId, ids).stream()
				.collect(Collectors.groupingBy(TurnoEvento::getTurnoId));

		Map<Long, ReprogramacionDeTurnoView> resultado = new HashMap<>();
		cadenas.forEach((turnoId, cadena) -> {
			ReprogramacionDeTurnoView vista = calcular(cadena, porTurno);
			if (vista != null) {
				resultado.put(turnoId, vista);
			}
		});
		if (resultado.isEmpty()) {
			return resultado;
		}
		Map<Long, AccountIdentity> actores = identidades.identidadesDe(resultado.values().stream()
				.map(ReprogramacionDeTurnoView::ultimoActorCuentaId).filter(Objects::nonNull).distinct().toList());
		resultado.replaceAll((id, vista) -> {
			AccountIdentity actor = vista.ultimoActorCuentaId() == null ? null : actores.get(vista.ultimoActorCuentaId());
			return actor == null ? vista : vista.conActorNombre(actor.nombreCompleto());
		});
		return resultado;
	}

	/**
	 * Los turnos de origen de {@code turno} (sin el), del mas cercano al original, cortando en el
	 * primero cuya sede el actor no puede leer con {@code turno:read}: el historial de un traslado no
	 * abre una sede que el lector no ve.
	 */
	List<Turno> origenesLegibles(OperatingActor actor, long organizationId, Turno turno) {
		if (turno.getTurnoOrigenId() == null) {
			return List.of();
		}
		List<Turno> cadena = cadenas(organizationId, List.of(turno)).getOrDefault(turno.getId(), List.of(turno));
		List<Turno> legibles = new ArrayList<>();
		Set<Long> sedesLegibles = new HashSet<>();
		sedesLegibles.add(turno.getConsultorioId());
		Instant ahora = Instant.now();
		for (Turno origen : cadena.subList(1, cadena.size())) {
			Long sede = origen.getConsultorioId();
			if (!sedesLegibles.contains(sede)) {
				boolean puede = permisos.evaluate(new PermissionQuery(actor.accountId(), PermissionCodes.TURNO_READ,
						organizationId, sede, null, ahora)).granted();
				if (!puede) {
					break;
				}
				sedesLegibles.add(sede);
			}
			legibles.add(origen);
		}
		return legibles;
	}

	/** Cada turno del lote con su cadena: el propio primero, el original al final. */
	Map<Long, List<Turno>> cadenas(long organizationId, Collection<Turno> lote) {
		Map<Long, Turno> porId = new HashMap<>();
		lote.forEach(t -> porId.put(t.getId(), t));
		Set<Long> pendientes = origenesSinLeer(lote, porId);
		for (int salto = 0; !pendientes.isEmpty() && salto < MAXIMO_DE_SALTOS; salto++) {
			List<Turno> leidos = turnos.findTodosPorId(organizationId, pendientes);
			leidos.forEach(t -> porId.put(t.getId(), t));
			pendientes = origenesSinLeer(leidos, porId);
		}
		Map<Long, List<Turno>> cadenas = new HashMap<>();
		for (Turno turno : lote) {
			List<Turno> cadena = new ArrayList<>();
			Set<Long> vistos = new HashSet<>();
			Turno actual = turno;
			while (actual != null && vistos.add(actual.getId())) {
				cadena.add(actual);
				actual = actual.getTurnoOrigenId() == null ? null : porId.get(actual.getTurnoOrigenId());
			}
			cadenas.put(turno.getId(), cadena);
		}
		return cadenas;
	}

	private static Set<Long> origenesSinLeer(Collection<Turno> turnos, Map<Long, Turno> leidos) {
		return turnos.stream().map(Turno::getTurnoOrigenId).filter(Objects::nonNull)
				.filter(id -> !leidos.containsKey(id)).collect(Collectors.toSet());
	}

	/**
	 * La derivacion, sin consultas: {@code null} si nadie movio el turno ni ninguno de sus origenes.
	 * El nombre del actor queda {@code null}; lo resuelve {@link #de} en lote.
	 *
	 * @param cadena el turno primero y su original al final
	 */
	static ReprogramacionDeTurnoView calcular(List<Turno> cadena, Map<Long, List<TurnoEvento>> eventosPorTurno) {
		int cantidad = cadena.size() - 1;
		TurnoEvento ultima = null;
		String ultimoTipo = null;
		for (int i = 0; i < cadena.size(); i++) {
			List<TurnoEvento> propios = eventosPorTurno.getOrDefault(cadena.get(i).getId(), List.of());
			for (TurnoEvento evento : propios) {
				if (evento.getTipo() == TipoEventoTurno.REPROGRAMACION) {
					cantidad++;
					if (esPosterior(evento, ultima)) {
						ultima = evento;
						ultimoTipo = REPROGRAMACION;
					}
				}
			}
			// Todo eslabon menos el original nacio de un traslado: su reserva ES el traslado.
			if (i < cadena.size() - 1) {
				TurnoEvento reserva = propios.stream().filter(e -> e.getTipo() == TipoEventoTurno.RESERVA)
						.min(Comparator.comparing(TurnoEvento::getOcurridoEn)).orElse(null);
				if (reserva != null && esPosterior(reserva, ultima)) {
					ultima = reserva;
					ultimoTipo = TRASLADO;
				}
			}
		}
		if (cantidad == 0) {
			return null;
		}
		Turno original = cadena.get(cadena.size() - 1);
		Instant inicioOriginal = eventosPorTurno.getOrDefault(original.getId(), List.of()).stream()
				.filter(e -> e.getTipo() == TipoEventoTurno.REPROGRAMACION && e.getInicioAnterior() != null)
				.min(Comparator.comparing(TurnoEvento::getOcurridoEn))
				.map(TurnoEvento::getInicioAnterior)
				.orElse(original.getInicio());
		return new ReprogramacionDeTurnoView(cantidad, inicioOriginal, ultimoTipo,
				ultima == null ? null : ultima.getOcurridoEn(),
				ultima == null ? null : ultima.getMotivo(),
				ultima == null ? null : ultima.getActorCuentaId(),
				null);
	}

	private static boolean esPosterior(TurnoEvento evento, TurnoEvento actual) {
		return actual == null || !evento.getOcurridoEn().isBefore(actual.getOcurridoEn());
	}
}
