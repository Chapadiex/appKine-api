package com.akine.offering.spi;

import java.util.List;

/**
 * Como se clasifica una oferta para un reporte: su servicio, la naturaleza de ese servicio y si es
 * grupal (AKINE-09.01b, RN-M23-005).
 *
 * <p>Viaja aparte de {@link OfertaSnapshot} porque el snapshot es lo que necesita la agenda y aca
 * hace falta el <b>servicio</b>, que vive en otra tabla. Agregarlo al snapshot obligaria a leer el
 * servicio en cada reserva para que lo use un reporte.
 *
 * <p><b>La naturaleza clasifica y no decide comportamiento</b> (RN-M06-005): un reporte que agrupa
 * por ella esta haciendo exactamente lo unico que la naturaleza autoriza. Y es un dato tipado del
 * catalogo, no un nombre visible: RNF-M23-009 prohibe inferir reglas desde el nombre del servicio.
 *
 * @param naturaleza el nombre de {@code offering.domain.Naturaleza}, como texto para que ningun
 *                   modulo importe el dominio de {@code offering}
 */
public record ClasificacionDeOferta(
		long ofertaId,
		String nombreComercial,
		boolean grupal,
		long servicioId,
		String servicioNombre,
		String naturaleza) {

	/**
	 * Las naturalezas del catalogo (V24, {@code ck_servicio_naturaleza}), en el orden en que las
	 * declara. {@code NaturalezasDeOfertaTest} verifica que coincidan con el enum del dominio.
	 */
	public static final List<String> NATURALEZAS =
			List.of("CLINICO", "TERAPEUTICO", "PREVENTIVO", "BIENESTAR");
}
