package com.akine.encounter.spi;

/**
 * Cita de la biblioteca que respalda una sugerencia de evolucion, tal como se guarda en
 * {@code sugerencia_evolucion.citas}. {@code sha256} es del texto del pasaje.
 *
 * <p>Vive fuera de {@code domain} porque {@code puntaje} es una similitud, no un importe, y la regla
 * de convenciones prohibe {@code double} en los campos de {@code ..domain..}.
 */
public record CitaGuardada(long libroId, String titulo, int paginaDesde, int paginaHasta,
		double puntaje, String sha256) {
}
