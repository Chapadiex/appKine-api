package com.akine.organization.infrastructure;

import com.akine.organization.domain.SolicitudDePrueba;
import com.akine.organization.domain.port.SolicitudDePruebaRepositoryPort;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Pedidos de prueba gratis (V28). Global: sin organization_id hasta aprobarse.
 *
 * <p>Dos consultas en vez de una con {@code :estado is null}: PostgreSQL no puede tipar un
 * parametro nulo dentro de esa comparacion. El texto llega siempre como patron ({@code %} =
 * todo).
 */
public interface SolicitudDePruebaRepository
		extends JpaRepository<SolicitudDePrueba, Long>, SolicitudDePruebaRepositoryPort {

	@Override
	default Page<SolicitudDePrueba> buscar(SolicitudDePrueba.Estado estado, String q, Pageable pageable) {
		String patron = TextoDeBusqueda.hay(q) ? TextoDeBusqueda.patronLike(q) : "%";
		return estado == null ? buscarTodas(patron, pageable) : buscarPorEstado(estado, patron, pageable);
	}

	@Query("""
			select s from SolicitudDePrueba s
			 where s.nombreCentro like :patron or s.email like :patron
			    or s.nombre like :patron or s.apellido like :patron
			 order by s.createdAt desc, s.id desc
			""")
	Page<SolicitudDePrueba> buscarTodas(@Param("patron") String patron, Pageable pageable);

	@Query("""
			select s from SolicitudDePrueba s
			 where s.estado = :estado
			   and (s.nombreCentro like :patron or s.email like :patron
			        or s.nombre like :patron or s.apellido like :patron)
			 order by s.createdAt desc, s.id desc
			""")
	Page<SolicitudDePrueba> buscarPorEstado(@Param("estado") SolicitudDePrueba.Estado estado,
			@Param("patron") String patron, Pageable pageable);
}
