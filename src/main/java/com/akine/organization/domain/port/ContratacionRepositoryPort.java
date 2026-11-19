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

	/** Centros vigentes en PRUEBA cuyo fin cae en [desde, hasta). */
	java.util.List<com.akine.organization.spi.PruebasGratis.PruebaEnCurso> pruebasQueTerminanEntre(
			java.time.Instant desde, java.time.Instant hasta);

	/** Cuentas con ORG_ADMIN vigente en la organizacion. */
	Set<Long> administradores(long organizationId);

	/** Reemplaza las filas: borra las que sobran e inserta las elegidas. */
	void reemplazar(long organizationId, Set<Servicio> servicios, Set<Adicional> adicionales);
}
