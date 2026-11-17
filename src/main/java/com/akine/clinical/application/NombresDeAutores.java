package com.akine.clinical.application;

import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Nombres visibles de quien cargo y quien reviso un antecedente o un adjunto (DP-47).
 *
 * <p>En lote y solo el nombre, mismo criterio que {@code SesionService} con las versiones de una
 * sesion: una cuenta que ya no resuelve deja el nombre ausente, no inventado.
 */
@Component
class NombresDeAutores {

	private final AccountIdentityDirectory identidades;

	NombresDeAutores(AccountIdentityDirectory identidades) {
		this.identidades = identidades;
	}

	List<AntecedenteView> antecedentes(Collection<AntecedenteView> vistas) {
		Map<Long, String> nombres = nombresDe(vistas.stream()
				.flatMap(v -> Stream.of(v.registradoPor(), v.revisadoPor())));
		return vistas.stream().map(v -> v.conNombres(nombres)).toList();
	}

	List<AdjuntoClinicoView> adjuntos(Collection<AdjuntoClinicoView> vistas) {
		Map<Long, String> nombres = nombresDe(vistas.stream()
				.flatMap(v -> Stream.of(v.subidoPor(), v.revisadoPor())));
		return vistas.stream().map(v -> v.conNombres(nombres)).toList();
	}

	AntecedenteView antecedente(AntecedenteView vista) {
		return antecedentes(List.of(vista)).get(0);
	}

	AdjuntoClinicoView adjunto(AdjuntoClinicoView vista) {
		return adjuntos(List.of(vista)).get(0);
	}

	/** Los nombres de un lote de cuentas. Una que no resuelve queda fuera del mapa. */
	Map<Long, String> nombres(Long... ids) {
		return nombresDe(Stream.of(ids));
	}

	private Map<Long, String> nombresDe(Stream<Long> ids) {
		List<Long> distintos = ids.filter(Objects::nonNull).distinct().toList();
		if (distintos.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> nombres = new HashMap<>();
		identidades.identidadesDe(distintos).forEach((id, identidad) -> {
			String nombre = identidad == null ? null : identidad.nombreCompleto();
			if (nombre != null) {
				nombres.put(id, nombre);
			}
		});
		return nombres;
	}
}
