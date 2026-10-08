package com.akine.contracting.application;

import com.akine.contracting.domain.exception.OfertaNoAccesibleException;
import com.akine.contracting.domain.exception.OfertaSinObraSocialException;
import com.akine.contracting.domain.exception.PracticaNoHabilitadaEnOfertaException;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;

/**
 * B-3 (RF-M16-008): la oferta de un arancel por oferta es de ESTA sede, admite obra social y
 * declara la practica (A-9).
 *
 * <p>Vive aparte desde B-7 porque la usan dos caminos que no pueden divergir: el alta unitaria
 * ({@link ArancelService#crear}) y la importacion masiva ({@link ImportacionArancelesService}).
 * Una fila importada tiene que fallar exactamente por lo mismo que fallaria su alta suelta.
 *
 * <p>Lectura viva de {@code offering.spi} sin lock de la oferta: si alguien quita la practica o
 * apaga la obra social justo despues, el arancel queda como dato que no resuelve —el devengo y la
 * cobertura aplicable vuelven a mirar la oferta al usarlo— y no como dato que cobra mal. No se
 * filtra por estado de la oferta: una oferta dada de baja no tiene practicas activas que declarar,
 * y {@code find} devuelve las inactivas a proposito.
 */
final class AsociacionDeOferta {

	private AsociacionDeOferta() {
		// Regla compartida.
	}

	static void exigir(
			OfertaDirectory ofertas,
			PracticasDeOfertaDirectory practicasDeOferta,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long practicaId) {

		ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNoAccesibleException(ofertaId));
		boolean admiteObraSocial = ofertas.precioDe(organizationId, consultorioId, ofertaId)
				.map(PrecioDeOferta::admiteObraSocial)
				.orElse(false);
		if (!admiteObraSocial) {
			throw new OfertaSinObraSocialException(ofertaId);
		}
		boolean declarada = practicasDeOferta
				.practicasHabilitadas(organizationId, consultorioId, ofertaId).stream()
				.anyMatch(p -> p.practicaId() == practicaId);
		if (!declarada) {
			throw new PracticaNoHabilitadaEnOfertaException(practicaId, ofertaId);
		}
	}
}
