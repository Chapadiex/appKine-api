package com.akine.scheduling.application;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtros de estado y de persona del calendario y de la agenda operativa (checklist de agenda §16).
 *
 * <p><b>El estado se compara contra el estado operativo derivado al leer</b> ({@code RESERVADO},
 * {@code CONFIRMADO}, {@code PRESENTE}, {@code RETIRADO}, {@code EN_ATENCION}, {@code ATENDIDO},
 * {@code AUSENTE}, {@code CANCELADO}; DP-05/DP-16), que es el que la agenda muestra. No se agrega
 * ningun estado nuevo al turno: el filtro solo mira lo que ya se calcula.
 *
 * @param estados   estados pedidos en mayusculas; vacio = todos
 * @param personaId persona del turno, o {@code null} para todas
 */
public record FiltroDeTurnos(Set<String> estados, Long personaId) {

	public static final FiltroDeTurnos NINGUNO = new FiltroDeTurnos(Set.of(), null);

	public FiltroDeTurnos {
		estados = estados == null ? Set.of() : Set.copyOf(estados);
	}

	public static FiltroDeTurnos de(Collection<String> estados, Long personaId) {
		Set<String> normalizados = estados == null ? Set.of() : estados.stream()
				.filter(estado -> estado != null && !estado.isBlank())
				.map(estado -> estado.strip().toUpperCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
		return new FiltroDeTurnos(normalizados, personaId);
	}

	/** Un filtro que pide {@code CANCELADO} los trae aunque no se haya pedido incluirlos aparte. */
	boolean incluyeCancelados(boolean incluirCancelados) {
		return incluirCancelados || estados.contains("CANCELADO");
	}

	boolean admitePersona(long personaDelTurno) {
		return personaId == null || personaId == personaDelTurno;
	}

	boolean admite(TurnoDelDiaView turno) {
		if (estados.isEmpty()) {
			return true;
		}
		// El operativo ya es RESERVADO o CONFIRMADO cuando no hay recepcion ni atencion, y CANCELADO o
		// AUSENTE cuando el turno lo es: comparar contra el no deja que "RESERVADO" traiga a quien ya
		// esta PRESENTE.
		String estado = turno.operativo() == null ? turno.estado() : turno.operativo().estadoOperativo();
		return estados.contains(estado);
	}
}
