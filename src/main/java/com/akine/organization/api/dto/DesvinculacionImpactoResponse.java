package com.akine.organization.api.dto;

import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Que queda pendiente si se desvincula a un colaborador (RN-M05-004).
 *
 * <h2>Esto NO es un rechazo</h2>
 *
 * <p>La regla dice que los turnos futuros afectados tienen que quedar <b>visibles para
 * resolucion</b>, y esa palabra es toda la diferencia con la baja de una sede, donde las
 * operaciones vigentes <b>impiden</b> la baja. Cuando alguien renuncia, renuncio: el sistema no
 * puede negarse a registrarlo porque tenga la agenda llena. Lo que tiene que hacer es no perder
 * esos turnos y decir cuantos son mientras quien decide todavia puede elegir el momento.
 *
 * <h2>Que informa</h2>
 *
 * <p>La primera sonda con contenido, no la suma: primero los turnos pendientes del profesional
 * en cualquier sede ({@code scheduling.infrastructure.ProfesionalConTurnosPendientes}, paquete
 * E-1) y, si no tiene, sus bloques y excepciones de disponibilidad vigentes
 * ({@code resource.infrastructure.ResourceDesvinculacionProbe}). {@code count} vale cero cuando
 * no queda nada pendiente.
 *
 * @param tipo  que son, en plural y en lenguaje del usuario: "turnos", "sesiones abiertas".
 *              {@code null} cuando no hay nada
 * @param count cuantos son. Cero cuando no hay nada
 * @param desde instante del primero, para que la pantalla pueda decir desde cuando.
 *              {@code null} cuando no hay nada
 */
@Schema(description = "Trabajo pendiente que quedaria sin dueño al desvincular")
public record DesvinculacionImpactoResponse(

		@Schema(description = "Que queda pendiente, en plural", example = "turnos") String tipo,
		@Schema(description = "Cuantos son. Cero si no hay nada", example = "0") long count,
		@Schema(description = "Instante del primero") Instant desde) {

	public static DesvinculacionImpactoResponse de(ColaboradorDesvinculacionProbe.Impacto impacto) {
		return new DesvinculacionImpactoResponse(
				impacto.tipo(), impacto.count(), impacto.desde());
	}
}
