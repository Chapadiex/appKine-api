package com.akine.organization.spi;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * "Hoy" de la organizacion, para las operaciones que no tienen sede (U-38, DP-43).
 *
 * <p><b>Por que existe.</b> El padron, las coberturas de la persona, las autorizaciones, las
 * ordenes medicas y los planes del financiador son de la organizacion, no de una sede: no hay
 * {@code consultorio.timezone} de donde sacar el dia. Hasta DP-43 esos sitios calculaban
 * {@code LocalDate.now()} con la zona de la JVM —UTC en la imagen—, y entre las 21 y las 24 de
 * Cordoba una vigencia que vencia ese dia se leia vencida tres horas antes.
 *
 * <p><b>La regla.</b> La zona es la columna {@code organization.timezone}, editable por el
 * {@code ORG_ADMIN} con {@code PATCH /organizations/{orgId}}. Por defecto es la de la
 * <b>primera sede</b> de la organizacion —V123 hizo el backfill con la de la sede mas antigua—
 * o {@link #POR_DEFECTO} si no tiene ninguna. Las operaciones con sede siguen usando
 * {@link ZonaDeSede}: esta interfaz es solo para las que no la tienen.
 *
 * <p><b>No falla.</b> Si la organizacion no resuelve o su zona no es valida, la implementacion
 * cae a {@link #POR_DEFECTO} con un WARN: una fila mal cargada no puede tumbar una lectura del
 * padron. Ver {@code docs/zonas-horarias.md}.
 */
public interface ZonaDeOrganizacion {

	/** La zona por defecto de una organizacion sin sedes ni zona valida. */
	ZoneId POR_DEFECTO = ZoneId.of("America/Argentina/Cordoba");

	/** La zona efectiva de la organizacion. Nunca {@code null}. */
	ZoneId de(long organizationId);

	/** El dia de hoy en la zona de la organizacion, segun {@code clock}. */
	default LocalDate hoy(long organizationId, Clock clock) {
		return LocalDate.ofInstant(clock.instant(), de(organizationId));
	}

	/** El dia de hoy en la zona de la organizacion. */
	default LocalDate hoy(long organizationId) {
		return hoy(organizationId, Clock.systemUTC());
	}
}
