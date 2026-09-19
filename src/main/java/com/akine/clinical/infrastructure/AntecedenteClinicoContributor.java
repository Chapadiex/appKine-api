package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Los antecedentes vigentes de la historia, indexados en el timeline (RF-M09-002, RF-M09-004).
 *
 * <p>Los antecedentes existen desde 04.01 y <b>nunca estuvieron indexados</b>: se veian solo al
 * abrir la ficha. Aparecen aca porque el timeline es la linea de tiempo de la historia y el dia en
 * que se registro una alergia es un hecho clinico datado como cualquier otro — de hecho es el que
 * mas caro sale no ver.
 *
 * <p><b>El evento no lleva la descripcion</b>, que es contenido clinico libre. Lo que viaja es el
 * tipo, que es una lista cerrada del producto: alcanza para que quien mira el timeline sepa que
 * ese dia se registro una alergia, y para decidir si abre la ficha con su propio permiso.
 *
 * <p>El instante es {@code registradoEn}: un antecedente no declara cuando ocurrio —nadie sabe el
 * dia exacto en que aparecio una alergia— sino cuando quedo asentado.
 */
@Component
public class AntecedenteClinicoContributor implements EventoClinicoContributor {

	/** Como se identifica esta fuente en el orden total del timeline y en el cursor. */
	static final String ORIGEN = "ANTECEDENTE_CLINICO";

	private final AntecedenteClinicoRepositoryPort antecedentes;

	public AntecedenteClinicoContributor(AntecedenteClinicoRepositoryPort antecedentes) {
		this.antecedentes = antecedentes;
	}

	@Override
	public List<EventoClinico> eventosDe(
			long organizationId, long historiaClinicaId, Instant hasta, int limite, Long casoId) {

		// 04.03: un antecedente es de la PERSONA y precede a cualquier caso —una alergia no es de
		// la rodilla—, asi que atribuirlo a uno seria inventar. Con filtro por caso, nada.
		if (casoId != null) {
			return List.of();
		}
		return antecedentes.buscarParaTimeline(organizationId, historiaClinicaId, hasta, limite)
				.stream()
				.map(antecedente -> new EventoClinico(
						antecedente.getRegistradoEn(),
						ORIGEN,
						antecedente.getTipo().name(),
						"Antecedente clinico",
						antecedente.getId()))
				.toList();
	}
}
