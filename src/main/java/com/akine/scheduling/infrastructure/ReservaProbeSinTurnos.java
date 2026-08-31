package com.akine.scheduling.infrastructure;

import com.akine.scheduling.spi.ReservaProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Implementacion de {@link ReservaProbe} para cuando todavia no existen los turnos.
 *
 * <p>Devuelve el mapa vacio, o sea "ningun slot tiene reservas". <b>Es correcto hoy y es falso
 * manana</b>: la tabla {@code turno} llega en AKINE-05.02 y esta clase se borra en esa etapa.
 *
 * <p>Se deja como bean explicito y no como un {@code Optional} vacio inyectado en el servicio a
 * proposito. Un opcional obligaria al motor a tener dos caminos —con sonda y sin sonda— y el
 * camino "sin sonda" no se ejercita nunca despues de 05.02, asi que envejece sin que ningun test
 * lo denuncie. Con un bean, el motor tiene un solo camino y lo unico que cambia es quien contesta.
 *
 * <p>Marcado {@code @Primary} no hace falta: cuando 05.02 registre su implementacion, esta clase
 * ya no existe. Si por algun motivo llegaran a convivir, Spring falla al arrancar por bean
 * duplicado, que es preferible a elegir una en silencio.
 */
@Component
public class ReservaProbeSinTurnos implements ReservaProbe {

	@Override
	public Map<Instant, Integer> reservasPorInicio(
			long organizationId,
			long consultorioId,
			long ofertaId,
			List<Long> recursoIds,
			Instant desde,
			Instant hasta) {

		return Map.of();
	}
}
