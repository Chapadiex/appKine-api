package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.EntradaClinicaVersion;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaVersionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Persistencia de {@link EntradaClinicaVersion}.
 *
 * <p><b>Solo escribe y lee. No actualiza y no borra</b>, y eso no es una omision de esta interfaz
 * sino la consecuencia de que la entity no tenga un solo campo mutable: una version es un hecho
 * pasado (ADR-0011).
 *
 * <p>Las dos consultas usan el unique {@code uk_entrada_version_numero}, que empieza por
 * {@code organization_id}: el tenant acota antes que nada (ADR-0004). La columna de tenant esta en
 * esta tabla aunque sea derivable de la entrada justamente para que estas consultas no necesiten
 * un join para filtrar por ella — ver el javadoc de la entity.
 */
public interface EntradaClinicaVersionRepository
		extends JpaRepository<EntradaClinicaVersion, Long>, EntradaClinicaVersionRepositoryPort {

	@Override
	@Query("""
			SELECT v FROM EntradaClinicaVersion v
			 WHERE v.organizationId = :organizationId
			   AND v.entradaClinicaId = :entradaClinicaId
			 ORDER BY v.numeroVersion DESC
			""")
	List<EntradaClinicaVersion> buscarDeEntrada(
			@Param("organizationId") Long organizationId,
			@Param("entradaClinicaId") Long entradaClinicaId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es un {@code default} y no una consulta derivada porque el corte en vacio tiene que estar
	 * de este lado: una {@code IN ()} vacia es un error de sintaxis en varios motores y una
	 * consulta inutil en todos. Spring Data ignora los metodos {@code default}, asi que esto no
	 * le pide nada al parser de nombres.
	 */
	@Override
	default List<EntradaClinicaVersion> buscarVigentesDe(
			Long organizationId, Collection<Long> entradaIds) {

		if (entradaIds == null || entradaIds.isEmpty()) {
			return List.of();
		}
		return buscarUltimasDe(organizationId, entradaIds);
	}

	/**
	 * La version de numero mas alto de cada entrada, en una sola consulta.
	 *
	 * <p>La subconsulta correlacionada resuelve el "maximo por grupo" sin traer todas las
	 * versiones a memoria: una entrada enmendada diez veces aporta una fila, no diez. La
	 * alternativa —una consulta por entrada— convierte una pantalla de 400 hechos en 400 viajes a
	 * la base.
	 */
	@Query("""
			SELECT v FROM EntradaClinicaVersion v
			 WHERE v.organizationId = :organizationId
			   AND v.entradaClinicaId IN :entradaIds
			   AND v.numeroVersion = (
			       SELECT MAX(u.numeroVersion) FROM EntradaClinicaVersion u
			        WHERE u.organizationId = v.organizationId
			          AND u.entradaClinicaId = v.entradaClinicaId)
			""")
	List<EntradaClinicaVersion> buscarUltimasDe(
			@Param("organizationId") Long organizationId,
			@Param("entradaIds") Collection<Long> entradaIds);
}
