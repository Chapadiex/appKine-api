package com.akine.contracting.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de un plan bajo un financiador (RF-M15-004).
 *
 * <p>El financiador no viaja en el comando: viene de la ruta, y el servicio lo resuelve contra la
 * base acotado al tenant antes de escribir nada. RN-M15-001 —"un plan pertenece a un
 * financiador"— la sostiene la estructura: la columna es {@code NOT NULL} y {@code updatable =
 * false}.
 *
 * <p>Tampoco lleva {@code Idempotency-Key}, por el mismo motivo que {@link FinanciadorAltaCommand}.
 *
 * @param vigenciaHasta ultimo dia INCLUSIVE. {@code null} = sin fin previsto, que es un estado
 *                      real y no un dato faltante
 * @param copago        {@code null} = sin copago declarado, distinto de cero. Viaja siempre con
 *                      {@link #moneda}: los dos o ninguno, y {@code BigDecimal} jamas {@code double}
 */
public record PlanAltaCommand(
		String codigo,
		String nombre,
		String descripcion,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		Boolean requiereAutorizacion,
		Boolean requiereCredencial,
		BigDecimal copago,
		String moneda) {
}
