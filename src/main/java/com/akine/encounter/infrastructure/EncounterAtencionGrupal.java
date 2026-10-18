package com.akine.encounter.infrastructure;

import com.akine.encounter.application.AtencionGrupalService;
import com.akine.encounter.spi.ActorDeAtencion;
import com.akine.encounter.spi.AtencionDeParticipante;
import com.akine.encounter.spi.AtencionGrupal;
import com.akine.encounter.spi.PedidoDeAtencion;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * El borde por donde {@code activity} abre y lista atenciones de participantes (AKINE-08.05).
 *
 * <p><b>Sin {@code @Transactional}</b>: lo son los metodos a los que delega, y se unen a la
 * transaccion del llamador. Mismo patron que {@code clinical.ClinicalDerivacionRegistry}.
 */
@Component
public class EncounterAtencionGrupal implements AtencionGrupal {

	private final AtencionGrupalService atenciones;

	public EncounterAtencionGrupal(AtencionGrupalService atenciones) {
		this.atenciones = atenciones;
	}

	@Override
	public AtencionDeParticipante iniciar(PedidoDeAtencion pedido) {
		return atenciones.iniciar(pedido);
	}

	@Override
	public List<AtencionDeParticipante> deLaClase(ActorDeAtencion actor, long claseId) {
		return atenciones.deLaClase(actor, claseId);
	}
}
