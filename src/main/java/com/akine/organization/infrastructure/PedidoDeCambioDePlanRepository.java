package com.akine.organization.infrastructure;

import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.organization.domain.port.PedidoDeCambioDePlanRepositoryPort;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Pedidos de cambio de plan (V50). Dos consultas en vez de una con {@code :estado is null}, por el
 * mismo motivo que {@link SolicitudDePruebaRepository}: PostgreSQL no tipa ese parametro nulo.
 */
public interface PedidoDeCambioDePlanRepository
		extends JpaRepository<PedidoDeCambioDePlan, Long>, PedidoDeCambioDePlanRepositoryPort {

	@Override
	default Page<PedidoDeCambioDePlan> buscar(PedidoDeCambioDePlan.Estado estado, String q, Pageable pageable) {
		String patron = TextoDeBusqueda.hay(q) ? TextoDeBusqueda.patronLike(q) : "%";
		return estado == null ? buscarTodos(patron, pageable) : buscarPorEstado(estado, patron, pageable);
	}

	@Query("""
			select p from PedidoDeCambioDePlan p, Organization o
			 where o.id = p.organizationId and o.name like :patron
			 order by p.createdAt desc, p.id desc
			""")
	Page<PedidoDeCambioDePlan> buscarTodos(@Param("patron") String patron, Pageable pageable);

	@Query("""
			select p from PedidoDeCambioDePlan p, Organization o
			 where o.id = p.organizationId and p.estado = :estado and o.name like :patron
			 order by p.createdAt desc, p.id desc
			""")
	Page<PedidoDeCambioDePlan> buscarPorEstado(@Param("estado") PedidoDeCambioDePlan.Estado estado,
			@Param("patron") String patron, Pageable pageable);
}
