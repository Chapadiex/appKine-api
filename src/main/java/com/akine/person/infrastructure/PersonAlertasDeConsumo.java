package com.akine.person.infrastructure;

import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.spi.AlertasDeConsumo;
import com.akine.person.spi.ConsumoARevisar;
import org.springframework.stereotype.Component;

/**
 * Publica la alerta "consumo a revisar" hacia los demas modulos (DP-13, AKINE C-4).
 *
 * <p>Adaptador delgado, mismo patron que {@link PersonConsumoDeAutorizaciones}: la transaccion la
 * abre —o la hereda de {@code billing}— el servicio.
 */
@Component
public class PersonAlertasDeConsumo implements AlertasDeConsumo {

	private final ConsumoDeAutorizacionService consumo;

	public PersonAlertasDeConsumo(ConsumoDeAutorizacionService consumo) {
		this.consumo = consumo;
	}

	@Override
	public int consumoARevisar(ConsumoARevisar hecho) {
		return consumo.consumoARevisar(hecho);
	}
}
