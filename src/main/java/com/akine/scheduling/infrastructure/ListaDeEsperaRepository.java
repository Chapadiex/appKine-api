package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.EntradaDeListaDeEspera;
import com.akine.scheduling.domain.EstadoListaDeEspera;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.ListaDeEsperaRepositoryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ListaDeEsperaRepository
		extends JpaRepository<EntradaDeListaDeEspera, Long>, ListaDeEsperaRepositoryPort {

	@Override
	@Query("""
			SELECT e FROM EntradaDeListaDeEspera e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND e.id = :entradaId
			""")
	Optional<EntradaDeListaDeEspera> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("entradaId") long entradaId);

	@Override
	default List<EntradaDeListaDeEspera> listar(
			long organizationId, long consultorioId, EstadoListaDeEspera estado, Long ofertaId,
			Long personaId, int pagina, int tamano) {
		return pagina(organizationId, consultorioId, estado, ofertaId, personaId,
				PageRequest.of(pagina, tamano));
	}

	// La prioridad es un enum en texto, asi que se ordena por su peso y no alfabeticamente.
	@Query("""
			SELECT e FROM EntradaDeListaDeEspera e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND (:estado IS NULL OR e.estado = :estado)
			   AND (:ofertaId IS NULL OR e.ofertaId = :ofertaId)
			   AND (:personaId IS NULL OR e.personaId = :personaId)
			 ORDER BY CASE e.prioridad
			            WHEN com.akine.scheduling.domain.PrioridadListaDeEspera.URGENTE THEN 0
			            WHEN com.akine.scheduling.domain.PrioridadListaDeEspera.ALTA THEN 1
			            ELSE 2 END,
			          e.creadaEn ASC, e.id ASC
			""")
	List<EntradaDeListaDeEspera> pagina(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("estado") EstadoListaDeEspera estado,
			@Param("ofertaId") Long ofertaId,
			@Param("personaId") Long personaId,
			Pageable pagina);

	@Override
	@Query("""
			SELECT COUNT(e) FROM EntradaDeListaDeEspera e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND (:estado IS NULL OR e.estado = :estado)
			   AND (:ofertaId IS NULL OR e.ofertaId = :ofertaId)
			   AND (:personaId IS NULL OR e.personaId = :personaId)
			""")
	long contar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("estado") EstadoListaDeEspera estado,
			@Param("ofertaId") Long ofertaId,
			@Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT e FROM EntradaDeListaDeEspera e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND e.ofertaId = :ofertaId
			   AND e.estado = com.akine.scheduling.domain.EstadoListaDeEspera.ACTIVA
			 ORDER BY e.creadaEn ASC, e.id ASC
			""")
	List<EntradaDeListaDeEspera> findActivasDeLaOferta(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("ofertaId") long ofertaId);
}
