package com.akine.activity.infrastructure;

import com.akine.activity.application.ApagadoDeRenovaciones;
import com.akine.organization.spi.ReaccionALaDesvinculacion;
import org.springframework.stereotype.Component;

/**
 * Engancha la renovacion de abonos a la desvinculacion de un colaborador (DP-40, U-43). La flecha es
 * {@code activity -> organization.spi}, la que ya existia: {@code organization} no sabe que hay
 * abonos.
 */
@Component
class RenovacionAlDesvincular implements ReaccionALaDesvinculacion {

	private final ApagadoDeRenovaciones apagado;

	RenovacionAlDesvincular(ApagadoDeRenovaciones apagado) {
		this.apagado = apagado;
	}

	@Override
	public void alDesvincular(Desvinculacion desvinculacion) {
		apagado.alPerderAlcance(desvinculacion.organizationId(), desvinculacion.accountId(),
				desvinculacion.causa().name(), desvinculacion.actorCuentaId(), desvinculacion.at());
	}
}
