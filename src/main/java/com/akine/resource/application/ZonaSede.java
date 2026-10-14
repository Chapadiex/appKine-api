package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ZonaDeSede;

import java.time.ZoneId;

/**
 * Resuelve la zona horaria EFECTIVA de una sede, en un solo lugar del modulo.
 *
 * <p>Toda regla local de M04 y M05 —una hora de atencion, un dia de cierre, el limite de una
 * ventana de impacto— se guarda en hora local de la sede y se proyecta a {@code Instant} con
 * esta zona (diseno §1). Que la conversion la resuelvan dos servicios con dos copias del mismo
 * metodo privado es como se termina con dos politicas distintas ante una sede mal cargada.
 *
 * <p>La politica —caer a UTC con un WARN si la sede no declara una zona valida— vive en
 * {@link ZonaDeSede}, compartida con los demas modulos.
 */
final class ZonaSede {

	private ZonaSede() {
		// Utilidad de resolucion.
	}

	static ZoneId de(ConsultorioSnapshot sede) {
		return ZonaDeSede.de(sede);
	}
}
