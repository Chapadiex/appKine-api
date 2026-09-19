package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Las entradas clinicas vigentes, indexadas en el timeline (RF-M09-004).
 *
 * <p><b>El evento no lleva el cuerpo de la entrada, y eso es la regla, no una omision.</b> El
 * timeline es un indice: dice que el dia tal se registro una evolucion, no que dice esa evolucion.
 * Quien quiera leerla va a {@code GET /api/v1/entradas-clinicas/{id}} con su propio
 * {@code hc:read}, y ese acceso deja su propio evento de auditoria. Copiar el texto aca lo
 * entregaria en una sola lectura y volveria inutil ese segundo registro.
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} por la misma razon que
 * {@link ClinicalHistoriaClinicaDirectory}: es un adaptador que traduce filas a la forma que el
 * {@code spi} declara, sin ninguna regla de negocio propia. La autorizacion y la auditoria las
 * resuelve {@code TimelineService} <b>una sola vez</b> para toda la pagina — repetirlas por
 * contribuyente produciria cuatro eventos de auditoria por lectura y ninguno diria mas que el
 * primero.
 */
@Component
public class EntradaClinicaContributor implements EventoClinicoContributor {

	/** Como se identifica esta fuente en el orden total del timeline y en el cursor. */
	static final String ORIGEN = "ENTRADA_CLINICA";

	private final EntradaClinicaRepositoryPort entradas;

	public EntradaClinicaContributor(EntradaClinicaRepositoryPort entradas) {
		this.entradas = entradas;
	}

	/**
	 * <p>El instante del hecho es {@code ocurrioEn} y no {@code registradaEn}: una evolucion se
	 * carga al final del dia y el timeline tiene que ordenarla por cuando paso, no por cuando se
	 * tipeo.
	 */
	@Override
	public List<EventoClinico> eventosDe(
			long organizationId, long historiaClinicaId, Instant hasta, int limite) {

		return entradas.buscarParaTimeline(organizationId, historiaClinicaId, hasta, limite)
				.stream()
				.map(entrada -> new EventoClinico(
						entrada.getOcurrioEn(),
						ORIGEN,
						entrada.getTipo().name(),
						"Entrada clinica",
						entrada.getId()))
				.toList();
	}
}
