package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Las altas de adjunto clinico, indexadas en el timeline (RF-M09-004, RF-M25-001).
 *
 * <p><b>El evento no lleva el titulo que escribio el profesional ni el nombre del archivo.</b> El
 * titulo de un estudio dice el diagnostico mas veces de lo que parece —"RMN rodilla derecha,
 * rotura"— y el timeline se le muestra tambien a quien entro por justificacion declarada desde el
 * mostrador. Lo que viaja es la <b>categoria</b>, que es una etiqueta cerrada del producto, y el
 * id para ir a buscar el resto con permiso propio.
 *
 * <p>Lo que se indexa es el <b>alta</b>: un adjunto reclasificado o dado de baja despues no se
 * mueve de lugar en la linea de tiempo, porque el hecho datado es que ese dia entro un documento a
 * la historia. El dado de baja, eso si, sale del indice.
 */
@Component
public class AdjuntoClinicoContributor implements EventoClinicoContributor {

	/** Como se identifica esta fuente en el orden total del timeline y en el cursor. */
	static final String ORIGEN = "ADJUNTO_CLINICO";

	private final AdjuntoClinicoRepositoryPort adjuntos;

	public AdjuntoClinicoContributor(AdjuntoClinicoRepositoryPort adjuntos) {
		this.adjuntos = adjuntos;
	}

	@Override
	public List<EventoClinico> eventosDe(
			long organizationId, long historiaClinicaId, Instant hasta, int limite) {

		return adjuntos.buscarParaTimeline(organizationId, historiaClinicaId, hasta, limite)
				.stream()
				.map(adjunto -> new EventoClinico(
						adjunto.getSubidoEn(),
						ORIGEN,
						adjunto.getCategoria().name(),
						"Adjunto clinico",
						adjunto.getId()))
				.toList();
	}
}
