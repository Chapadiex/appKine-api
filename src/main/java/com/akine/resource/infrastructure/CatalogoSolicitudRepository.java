package com.akine.resource.infrastructure;

import com.akine.resource.domain.CatalogoSolicitud;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Acceso a las solicitudes de alta de concepto global (RF-M06-005).
 *
 * <p>Esta tabla SI lleva {@code organization_id NOT NULL}: una solicitud siempre la hace un
 * tenant concreto, asi que el filtro es el de siempre y no hace falta ningun centinela de
 * duenio. Lo unico particular es la bandeja de la plataforma, que es la unica consulta del
 * modulo que cruza tenants — y solo la alcanza un administrador de plataforma, verificado por
 * el servicio antes de llamar.
 */
public interface CatalogoSolicitudRepository
		extends JpaRepository<CatalogoSolicitud, Long>, CatalogoRepositoryPorts.SolicitudPort {

	@Override
	@Query(value = """
			SELECT * FROM catalogo_solicitud
			 WHERE organization_id = :organizationId
			   AND (:estado = '' OR estado = :estado)
			 ORDER BY created_at DESC, id DESC
			""", nativeQuery = true)
	List<CatalogoSolicitud> listarPorTenant(
			@Param("organizationId") Long organizationId, @Param("estado") String estado);

	@Override
	@Query(value = """
			SELECT * FROM catalogo_solicitud
			 WHERE (:estado = '' OR estado = :estado)
			 ORDER BY created_at DESC, id DESC
			""", nativeQuery = true)
	List<CatalogoSolicitud> listarTodas(@Param("estado") String estado);
}
