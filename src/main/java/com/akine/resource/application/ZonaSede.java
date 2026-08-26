package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;

/**
 * Resuelve la zona horaria EFECTIVA de una sede, en un solo lugar del modulo.
 *
 * <p>Toda regla local de M04 y M05 —una hora de atencion, un dia de cierre, el limite de una
 * ventana de impacto— se guarda en hora local de la sede y se proyecta a {@code Instant} con
 * esta zona (diseno §1). Que la conversion la resuelvan dos servicios con dos copias del mismo
 * metodo privado es como se termina con dos politicas distintas ante una sede mal cargada.
 *
 * <p><b>Cae a UTC si la sede no declara zona, y no falla.</b> {@code consultorio.timezone} es
 * NOT NULL desde V18 y {@code organization} ya valida el valor al dar de alta la sede
 * (AKINE-02.01), asi que este camino es defensivo: un huso invalido en una fila vieja no puede
 * tumbar la edicion del horario ni la pantalla de disponibilidad. Queda el WARN para que la fila
 * se pueda encontrar y corregir.
 */
final class ZonaSede {

	private static final Logger log = LoggerFactory.getLogger(ZonaSede.class);

	private static final ZoneId UTC = ZoneId.of("UTC");

	private ZonaSede() {
		// Utilidad de resolucion.
	}

	static ZoneId de(ConsultorioSnapshot sede) {
		if (sede.timezone() == null || sede.timezone().isBlank()) {
			return UTC;
		}
		try {
			return ZoneId.of(sede.timezone());
		} catch (RuntimeException husoDesconocido) {
			log.warn("Sede con zona horaria invalida, se usa UTC: consultorioId={} timezone={}",
					sede.id(), sede.timezone());
			return UTC;
		}
	}
}
