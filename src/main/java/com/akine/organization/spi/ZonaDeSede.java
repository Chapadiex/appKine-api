package com.akine.organization.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * "Hoy" en la sede: la unica zona con sentido para una regla local (AKINE-02.01).
 *
 * <p><b>Por que existe.</b> {@code LocalDate.now()} sin zona usa la de la JVM, que en la imagen es
 * UTC: entre las 21 y las 24 de Cordoba una vigencia que vence ese dia se lee vencida, y un
 * precio o un arancel que arranca manana se lee vigente. Toda operacion que tiene una sede —en la
 * ruta, en la entidad o en el contexto— calcula su "hoy" aca, con la zona IANA de la sede.
 *
 * <p>Las operaciones de la organizacion SIN sede (padron, coberturas de la persona, autorizaciones,
 * catalogo) no pasan por aca: usan la zona de la organizacion, {@link ZonaDeOrganizacion} (U-38,
 * DP-43). Ver {@code docs/zonas-horarias.md}.
 *
 * <p><b>Cae a UTC y no falla</b> si la sede no declara zona, la declara invalida o no resuelve:
 * {@code consultorio.timezone} es NOT NULL desde V18 y {@code organization} la valida al dar de
 * alta la sede, asi que el camino es defensivo. Una fila vieja mal cargada no puede tumbar una
 * lectura; queda el WARN para encontrarla.
 */
public final class ZonaDeSede {

	private static final Logger log = LoggerFactory.getLogger(ZonaDeSede.class);

	private ZonaDeSede() {
		// Utilidad.
	}

	/** La zona efectiva de la sede, o UTC si no declara una valida. */
	public static ZoneId de(ConsultorioSnapshot sede) {
		String zona = sede.timezone();
		if (zona == null || zona.isBlank()) {
			return ZoneOffset.UTC;
		}
		try {
			return ZoneId.of(zona);
		} catch (DateTimeException invalida) {
			log.warn("Sede con zona horaria invalida, se usa UTC: consultorioId={} timezone={}",
					sede.id(), zona);
			return ZoneOffset.UTC;
		}
	}

	/** La zona efectiva de la sede, o UTC si la sede no resuelve. */
	public static ZoneId de(ConsultorioDirectory consultorios, long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.map(ZonaDeSede::de)
				.orElse(ZoneOffset.UTC);
	}

	/** El dia de hoy en la sede, segun {@code clock}. */
	public static LocalDate hoy(ConsultorioSnapshot sede, Clock clock) {
		return LocalDate.ofInstant(clock.instant(), de(sede));
	}

	/** El dia de hoy en la sede, segun {@code clock}; en UTC si la sede no resuelve. */
	public static LocalDate hoy(
			ConsultorioDirectory consultorios, long organizationId, long consultorioId, Clock clock) {
		return LocalDate.ofInstant(clock.instant(), de(consultorios, organizationId, consultorioId));
	}
}
