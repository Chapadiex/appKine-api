package com.akine.organization.infrastructure;

import com.akine.organization.domain.LogoResumen;
import com.akine.organization.domain.OrganizationLogo;
import com.akine.organization.domain.port.OrganizationLogoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Acceso a {@code organization_logo}, propiedad del modulo {@code organization}. */
public interface OrganizationLogoRepository
		extends JpaRepository<OrganizationLogo, Long>, OrganizationLogoRepositoryPort {

	@Override
	@Query("""
			select l.variante as variante, l.contentType as contentType, l.sha256 as sha256
			from OrganizationLogo l where l.organizationId = :organizationId""")
	List<LogoResumen> resumenes(@Param("organizationId") Long organizationId);

	@Override
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO organization_logo
			    (organization_id, variante, content_type, contenido, tamano_bytes, sha256, created_at, updated_at)
			VALUES (:organizationId, :variante, :contentType, :contenido, :tamanoBytes, :sha256, now(), now())
			ON CONFLICT (organization_id, variante) DO UPDATE
			SET content_type = EXCLUDED.content_type, contenido = EXCLUDED.contenido,
			    tamano_bytes = EXCLUDED.tamano_bytes, sha256 = EXCLUDED.sha256, updated_at = now()""",
			nativeQuery = true)
	void guardar(@Param("organizationId") Long organizationId, @Param("variante") String variante,
			@Param("contentType") String contentType, @Param("contenido") byte[] contenido,
			@Param("tamanoBytes") int tamanoBytes, @Param("sha256") String sha256);

	@Override
	@Modifying(clearAutomatically = true)
	@Query(value = "DELETE FROM organization_logo WHERE organization_id = :organizationId AND variante = :variante",
			nativeQuery = true)
	int eliminar(@Param("organizationId") Long organizationId, @Param("variante") String variante);
}
