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
 * <h2>Hoy siempre responde que no hay nada</h2>
 *
 * <p>M12 (agenda) no existe, asi que no hay ninguna sonda enchufada y {@code count} vale cero.
 * La operacion se publica igual para que la pantalla de desvinculacion se escriba una sola vez:
 * el dia que la agenda enchufe su sonda, el numero aparece <b>sin cambiar el contrato</b>.
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
