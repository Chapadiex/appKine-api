package com.akine.reporting.application;

import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ReporteCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un reporte ya resuelto, tal como sale de {@code application}.
 *
 * <h2>{@code omitidas} y {@code advertencias} no son adornos</h2>
 *
 * <p>Las dos existen por la misma razon: <b>un cero sin explicacion es una mentira que el operador
 * no puede detectar.</b>
 *
 * <ul>
 *   <li>{@code omitidas} — secciones cuyo permiso el actor no tiene. No se pidieron, no se
 *       calcularon y <b>no produjeron 403</b>. Viajan declaradas con el codigo de permiso que
 *       falta, porque omitir en silencio haria que la pantalla dijera "cero turnos" donde en
 *       realidad dice "no podes ver los turnos".</li>
 *   <li>{@code advertencias} — indicadores que valen cero <b>por construccion</b>. Es el caso de
 *       {@code prestado}: no existe ninguna obligacion de financiador y nada la produce.</li>
 * </ul>
 *
 * @param zona        zona IANA de la sede con la que se recorto el periodo
 * @param generadoEn  instante de la corrida. Un reporte del periodo abierto cambia entre dos
 *                    consultas, y sin este dato no hay forma de explicar por que
 */
public record ReporteView(
		ReporteCode reporte,
		long consultorioId,
		LocalDate desde,
		LocalDate hasta,
		String zona,
		Instant generadoEn,
		List<AporteDeReporte> secciones,
		List<SeccionOmitida> omitidas,
		List<AdvertenciaDeReporte> advertencias) {

	public ReporteView {
		secciones = secciones == null ? List.of() : List.copyOf(secciones);
		omitidas = omitidas == null ? List.of() : List.copyOf(omitidas);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
	}

	/**
	 * Una seccion que el actor no puede ver, con el codigo de permiso que le falta.
	 *
	 * <p>Viaja el codigo y no una frase: la pantalla decide como decirlo, y el frontend ya conoce
	 * el catalogo porque {@code /me/permissions} se lo entrega. Misma decision que el Paciente 360.
	 */
	public record SeccionOmitida(String seccion, String permisoRequerido) {
	}
}
