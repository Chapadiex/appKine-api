package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.SesionMedicion;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a las mediciones de una sesion.
 *
 * <p>Todas las consultas son JPQL y no nativas: esta tabla no tiene ninguna columna generada que
 * obligue a bajar a SQL —a diferencia del catalogo, donde {@code owner_key} no existe en la
 * entidad— y Hibernate valida el JPQL contra el modelo al arrancar la aplicacion. Con una nativa,
 * un nombre de columna mal escrito aparece recien en runtime, y esta etapa <b>no puede correr
 * tests de integracion</b>.
 */
public interface SesionMedicionRepository
		extends JpaRepository<SesionMedicion, Long>, SesionMedicionRepositoryPort {

	@Override
	@Query("""
			SELECT m FROM SesionMedicion m
			 WHERE m.organizationId = :organizationId
			   AND m.sesionId = :sesionId
			   AND m.definicionId = :definicionId
			   AND m.lateralidad = :lateralidad
			""")
	Optional<SesionMedicion> buscarEnSesion(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId,
			@Param("definicionId") long definicionId,
			@Param("lateralidad") LateralidadMedicion lateralidad);

	/**
	 * {@inheritDoc}
	 *
	 * <p>El orden es el del unique —{@code definicion, lateralidad}— para que la pantalla muestre
	 * izquierda y derecha juntas y en el mismo orden en cada carga. Un listado clinico cuyo orden
	 * cambia entre dos lecturas obliga a releer todo para encontrar el valor que se acaba de
	 * cargar.
	 */
	@Override
	@Query("""
			SELECT m FROM SesionMedicion m
			 WHERE m.organizationId = :organizationId
			   AND m.sesionId = :sesionId
			 ORDER BY m.definicionNombre ASC, m.definicionId ASC, m.lateralidad ASC
			""")
	List<SesionMedicion> listarDeSesion(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);

	@Override
	@Query("""
			SELECT m FROM SesionMedicion m
			 WHERE m.organizationId = :organizationId
			   AND m.sesionId IN (:sesionIds)
			 ORDER BY m.definicionNombre ASC, m.definicionId ASC, m.lateralidad ASC
			""")
	List<SesionMedicion> listarDeSesiones(
			@Param("organizationId") long organizationId,
			@Param("sesionIds") Collection<Long> sesionIds);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Devuelve el <b>id</b> y no la {@code Sesion}: esa entity es de este mismo modulo, pero
	 * traerla entera para quedarse con la clave obligaria a cargar el borrador y la evaluacion
	 * completa de una sesion que nadie va a mostrar.
	 *
	 * <p>{@code EXISTS} sobre las mediciones y no un {@code JOIN}: con el join, una sesion con
	 * doce mediciones produciria doce filas que el {@code LIMIT 1} recortaria por casualidad del
	 * orden. El {@code EXISTS} dice lo que se quiere decir —"que tenga alguna"— y se detiene en la
	 * primera.
	 *
	 * <p>{@code :casoId IS NULL OR ...} y no un centinela: aca el parametro es un {@code Long}
	 * tipado en JPQL, asi que Hibernate no tiene que inferir nada —el problema del "could not
	 * determine type" es de las consultas nativas—. Es la misma forma que
	 * {@code buscarCerradasParaTimeline} ya usa para el mismo filtro.
	 */
	@Override
	@Query("""
			SELECT s.id FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.historiaClinicaId = :historiaClinicaId
			   AND s.estado = com.akine.encounter.domain.EstadoSesion.CERRADA
			   AND s.cerradaEn IS NOT NULL
			   AND s.iniciadaEn < :antesDe
			   AND s.deletedAt IS NULL
			   AND (:casoId IS NULL OR s.casoId = :casoId)
			   AND EXISTS (SELECT 1 FROM SesionMedicion m WHERE m.sesionId = s.id)
			 ORDER BY s.iniciadaEn DESC, s.id DESC
			 LIMIT 1
			""")
	Optional<Long> idSesionAnteriorConMediciones(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId,
			@Param("casoId") Long casoId,
			@Param("antesDe") Instant antesDe);
}
