package com.akine.organization.domain.port;

import com.akine.organization.domain.LogoResumen;
import com.akine.organization.domain.LogoVariante;
import com.akine.organization.domain.OrganizationLogo;

import java.util.List;
import java.util.Optional;

/** Acceso a {@code organization_logo}: a lo sumo un logo por tenant y tema. */
public interface OrganizationLogoRepositoryPort {

	/** Que logos hay, sin traer su contenido. */
	List<LogoResumen> resumenes(Long organizationId);

	Optional<OrganizationLogo> findByOrganizationIdAndVariante(Long organizationId, LogoVariante variante);

	/** Upsert: subir un logo donde ya habia uno lo reemplaza. */
	void guardar(Long organizationId, String variante, String contentType, byte[] contenido,
			int tamanoBytes, String sha256);

	/** @return filas borradas: 0 si no habia logo */
	int eliminar(Long organizationId, String variante);
}
