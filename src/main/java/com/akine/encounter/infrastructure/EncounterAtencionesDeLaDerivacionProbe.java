package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.AtencionesDeLaDerivacionProbe;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.springframework.stereotype.Component;

/**
 * Le responde a {@code clinical} si de una derivacion cuelga una Sesion (AKINE-08.05).
 *
 * <p>Es el cerrojo que 08.04 dejo anunciado: revertir una derivacion con atencion es 409. Vive
 * aca y habla el {@code spi} del vecino, como {@link EncounterRealizadoEnElCasoProbe}: la arista es
 * {@code encounter -> clinical.spi}, que ya existe. No escribe nada y no devuelve nada clinico.
 *
 * <p>Sin {@code @Transactional}: se une a la transaccion de la reversion, que es la que importa.
 */
@Component
public class EncounterAtencionesDeLaDerivacionProbe implements AtencionesDeLaDerivacionProbe {

	private final SesionRepositoryPort sesiones;

	public EncounterAtencionesDeLaDerivacionProbe(SesionRepositoryPort sesiones) {
		this.sesiones = sesiones;
	}

	@Override
	public boolean tieneAtencion(long organizationId, long derivacionId) {
		return sesiones.findVivaPorDerivacion(organizationId, derivacionId).isPresent();
	}
}
