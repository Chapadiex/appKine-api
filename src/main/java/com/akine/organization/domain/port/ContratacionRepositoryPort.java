package com.akine.organization.domain.port;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;

import java.util.Set;

/** Servicios y adicionales contratados por una organizacion (tablas de V28). */
public interface ContratacionRepositoryPort {

	Set<Servicio> serviciosDe(long organizationId);

	Set<Adicional> adicionalesDe(long organizationId);

	/** Reemplaza las filas: borra las que sobran e inserta las elegidas. */
	void reemplazar(long organizationId, Set<Servicio> servicios, Set<Adicional> adicionales);
}
