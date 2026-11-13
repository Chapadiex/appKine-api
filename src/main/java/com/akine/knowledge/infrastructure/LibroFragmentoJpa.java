package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.LibroFragmento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface LibroFragmentoJpa extends JpaRepository<LibroFragmento, Long> {

	@Query(value = """
			SELECT f.id FROM libro_fragmento f
			  JOIN libro l ON l.id = f.libro_id
			 WHERE (f.modelo_embeddings IS NULL OR f.modelo_embeddings <> :modelo)
			   AND f.sospechoso = false
			   AND l.activo = true AND l.estado = 'LISTO'
			 LIMIT :lote
			 FOR UPDATE OF f SKIP LOCKED
			""", nativeQuery = true)
	List<Long> reclamarSinVector(@Param("modelo") String modelo, @Param("lote") int lote);

	@Modifying
	@Query(value = "UPDATE libro_fragmento SET vector = CAST(:vector AS vector), modelo_embeddings = :modelo WHERE id = :id",
			nativeQuery = true)
	int actualizarVector(@Param("id") long id, @Param("vector") String vector,
			@Param("modelo") String modelo);

	@Modifying
	@Query(value = "DELETE FROM libro_fragmento WHERE libro_id = :libroId", nativeQuery = true)
	int borrarDeLibro(@Param("libroId") long libroId);

	long countByLibroId(long libroId);

	long countByLibroIdAndVectorIsNotNull(long libroId);

	List<LibroFragmento> findByLibroIdAndSospechosoTrueOrderByOrden(long libroId);
}
