package com.akine.resource.infrastructure;

import com.akine.resource.domain.NomencladorItem;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a las vigencias de los codigos de un nomenclador.
 *
 * <p><b>{@link #vigenciasDelCodigo} se ejecuta siempre despues del lock sobre el nomenclador
 * padre</b>, nunca antes. Adelantarla fijaria el snapshot de la transaccion y devolveria datos
 * anteriores al commit del competidor aunque el lock ya se hubiera adquirido: el lock serializa
 * el acceso, no la visibilidad. El orden esta escrito en {@code CatalogoService} y en la
 * cabecera de la migracion V20.
 *
 * <p>{@link #resolverEn} es la consulta que RN-M06-003 necesita y la que M16 va a ejecutar por
 * cada linea de un convenio: la cubre {@code ix_nomenclador_item_resolucion}.
 */
public interface NomencladorItemRepository
		extends JpaRepository<NomencladorItem, Long>,
		CatalogoRepositoryPorts.NomencladorItemPort {

	@Override
	@Query(value = """
			SELECT * FROM nomenclador_item
			 WHERE id = :id
			   AND owner_key IN (:owners)
			""", nativeQuery = true)
	Optional<NomencladorItem> findVisible(
			@Param("id") Long id, @Param("owners") Collection<Long> owners);

	@Override
	@Query(value = """
			SELECT * FROM nomenclador_item
			 WHERE nomenclador_id = :nomencladorId
			   AND owner_key IN (:owners)
			   AND (:activoFiltro = -1 OR active = :activoFiltro)
			   AND (:practicaId = -1 OR practica_id = :practicaId)
			   AND (:codigo = '' OR codigo = :codigo)
			 ORDER BY codigo ASC, valid_from DESC
			""", nativeQuery = true)
	List<NomencladorItem> listar(
			@Param("nomencladorId") Long nomencladorId,
			@Param("owners") Collection<Long> owners,
			@Param("activoFiltro") int activoFiltro,
			@Param("practicaId") long practicaId,
			@Param("codigo") String codigo);

	/**
	 * Solo las vigencias VIGENTES administrativamente. Las dadas de baja quedan fuera porque
	 * una vigencia dada de baja no reserva su ventana: el codigo se puede volver a abrir.
	 */
	@Override
	@Query(value = """
			SELECT * FROM nomenclador_item
			 WHERE nomenclador_id = :nomencladorId
			   AND codigo = :codigo
			   AND active = 1
			 ORDER BY valid_from ASC
			""", nativeQuery = true)
	List<NomencladorItem> vigenciasDelCodigo(
			@Param("nomencladorId") Long nomencladorId, @Param("codigo") String codigo);

	/**
	 * Que decia el codigo el dia {@code at}.
	 *
	 * <p>No exige {@code active = 1}: RN-M06-002 pide que el historico siga resolviendo aunque
	 * la vigencia se haya dado de baja despues. Lo que si exige es que {@code at} caiga dentro
	 * de la ventana, con el limite superior exclusivo — dos vigencias consecutivas no se pisan
	 * en el microsegundo del borde y la respuesta es siempre una sola fila.
	 */
	@Override
	@Query(value = """
			SELECT * FROM nomenclador_item
			 WHERE nomenclador_id = :nomencladorId
			   AND codigo = :codigo
			   AND valid_from <= :at
			   AND (valid_until IS NULL OR valid_until > :at)
			 ORDER BY valid_from DESC
			 LIMIT 1
			""", nativeQuery = true)
	Optional<NomencladorItem> resolverEn(
			@Param("nomencladorId") Long nomencladorId,
			@Param("codigo") String codigo,
			@Param("at") Instant at);

	@Override
	@Query(value = """
			SELECT COUNT(*) FROM nomenclador_item
			 WHERE nomenclador_id = :nomencladorId
			   AND active = 1
			""", nativeQuery = true)
	long contarVigentesPorNomenclador(@Param("nomencladorId") Long nomencladorId);
}
