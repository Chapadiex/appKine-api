package com.akine.person.application;

import com.akine.person.domain.Autorizacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Los datos editables de una autorizacion, para describir en el historial que cambio (DP-23).
 *
 * <p>El {@code detalle} que produce es administrativo: numero, orden, cantidad y fechas. Las
 * observaciones son texto libre y pueden traer cualquier cosa, asi que se nombra que cambiaron
 * pero <b>no se copia su contenido</b>: la autorizacion ya lo guarda, y el historial no tiene por
 * que multiplicarlo.
 */
record CambiosDeAutorizacion(
		String numero,
		Long ordenMedicaId,
		Integer cantidadAutorizada,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String observaciones) {

	static CambiosDeAutorizacion de(Autorizacion autorizacion) {
		return new CambiosDeAutorizacion(
				autorizacion.getNumero(),
				autorizacion.getOrdenMedicaId(),
				autorizacion.getCantidadAutorizada(),
				autorizacion.getVigenciaDesde(),
				autorizacion.getVigenciaHasta(),
				autorizacion.getObservaciones());
	}

	/** Lo que declara el alta: cantidad y vigencia. */
	static String describirAlta(Autorizacion autorizacion) {
		return "cantidadAutorizada: " + texto(autorizacion.getCantidadAutorizada())
				+ "; vigencia: " + autorizacion.getVigenciaDesde()
				+ " a " + texto(autorizacion.getVigenciaHasta());
	}

	/** Que cambio de {@code this} a {@code despues}, o vacio si nada. */
	Optional<String> describirDiferencia(CambiosDeAutorizacion despues) {
		List<String> cambios = new ArrayList<>();
		comparar(cambios, "numero", numero, despues.numero);
		comparar(cambios, "ordenMedicaId", ordenMedicaId, despues.ordenMedicaId);
		comparar(cambios, "cantidadAutorizada", cantidadAutorizada, despues.cantidadAutorizada);
		comparar(cambios, "vigenciaDesde", vigenciaDesde, despues.vigenciaDesde);
		comparar(cambios, "vigenciaHasta", vigenciaHasta, despues.vigenciaHasta);
		if (!Objects.equals(observaciones, despues.observaciones)) {
			cambios.add("observaciones");
		}
		return cambios.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", cambios));
	}

	private static void comparar(List<String> cambios, String campo, Object antes, Object despues) {
		if (!Objects.equals(antes, despues)) {
			cambios.add(campo + ": " + texto(antes) + " -> " + texto(despues));
		}
	}

	private static String texto(Object valor) {
		return valor == null ? "sin dato" : valor.toString();
	}
}
