package com.akine.contracting.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edicion parcial de un plan, incluido el cierre de vigencia (RF-M15-005).
 *
 * <p>Semantica de PATCH: los {@code null} no se tocan. Ni el codigo ni el financiador estan, y las
 * dos ausencias son la misma decision: son la identidad del plan, y las coberturas ya firmadas la
 * referencian.
 *
 * <p><b>Cerrar la vigencia es esto, no una operacion aparte</b>: mandar {@code vigenciaHasta} con
 * una fecha. RF-M15-005 dice "actualizar o cerrar vigencia" y son la misma escritura. Cerrar la
 * vigencia <b>no</b> es dar de baja: el plan queda activo y consultable y lo unico que cambia es
 * que deja de ofrecerse para selecciones posteriores a esa fecha.
 *
 * <p>La vigencia se valida como par aunque llegue de a una: mandar solo {@code vigenciaHasta} se
 * compara contra el {@code vigenciaDesde} guardado. Validar cada campo por separado dejaria pasar
 * una ventana invertida que despues rechaza el CHECK con un 500 en vez de un 400.
 */
public record PlanEdicionCommand(
		String nombre,
		String descripcion,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		Boolean requiereAutorizacion,
		Boolean requiereCredencial,
		BigDecimal copago,
		String moneda,
		long expectedVersion) {
}
