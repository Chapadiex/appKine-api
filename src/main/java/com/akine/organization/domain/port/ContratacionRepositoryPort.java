package com.akine.organization.domain.port;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;

import java.util.Set;

/** Servicios y adicionales contratados por una organizacion (tablas de V28). */
public interface ContratacionRepositoryPort {

	Set<Servicio> serviciosDe(long organizationId);

	Set<Adicional> adicionalesDe(long organizationId);

	/** Si la plataforma eligio la contratacion (alta o cambio); si no, el centro tiene todo. */
	boolean definida(long organizationId);

	/**
	 * Si el centro nacio en prueba gratis, que es como lo crea el alta de la plataforma: el
	 * autoregistro lo crea ACTIVA. Se lee de la primera transicion de la suscripcion.
	 */
	boolean nacioEnPrueba(long organizationId);

	/** Centros vigentes en PRUEBA cuyo fin cae en [desde, hasta). */
	java.util.List<com.akine.organization.spi.PruebasGratis.PruebaEnCurso> pruebasQueTerminanEntre(
			java.time.Instant desde, java.time.Instant hasta);

	/** Centros vigentes con la suscripcion ACTIVA guardada cuyo pagado_hasta cae en [desde, hasta). */
	java.util.List<com.akine.organization.spi.PruebasGratis.PagoQueVence> pagosQueVencenEntre(
			java.time.Instant desde, java.time.Instant hasta);

	/** Cuentas con ORG_ADMIN vigente en la organizacion. */
	Set<Long> administradores(long organizationId);

	/** Reemplaza las filas: borra las que sobran e inserta las elegidas. */
	void reemplazar(long organizationId, Set<Servicio> servicios, Set<Adicional> adicionales);

	/**
	 * De los centros dados, los que tienen el adicional: contratado, o sin contratacion definida
	 * (centros anteriores al 18/11/2026, que tienen todo). Una sola consulta para una pagina entera.
	 */
	Set<Long> conAdicional(java.util.Collection<Long> organizationIds, Adicional adicional);

	/**
	 * Los servicios de cada centro dado, con la misma regla que {@link #serviciosDe}: sin contratacion
	 * definida, todos. Una sola consulta para una pagina entera; un centro sin suscripcion no viene.
	 */
	java.util.Map<Long, Set<Servicio>> serviciosDeVarios(java.util.Collection<Long> organizationIds);
}
