package com.akine.billing.infrastructure;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ObligacionRepository
		extends JpaRepository<Obligacion, Long>, ObligacionRepositoryPort {

	@Override
	@Query("""
			SELECT o FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.consultorioId = :consultorioId
			   AND o.id = :obligacionId
			""")
	Optional<Obligacion> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("obligacionId") long obligacionId);

	/**
	 * <p>Sin filtro por sede ni por organizacion a proposito: una sesion pertenece a UNA sede, asi
	 * que agregarlos no acota nada y abriria la puerta a que un llamador pase el ambito equivocado y
	 * reciba {@code empty} en vez de la obligacion que existe — con lo cual devengaria una segunda
	 * y chocaria contra el unique.
	 */
	@Override
	@Query("""
			SELECT o FROM Obligacion o
			 WHERE o.sesionId = :sesionId
			   AND o.responsable = :responsable
			   AND o.deletedAt IS NULL
			""")
	Optional<Obligacion> findPorPrestacion(
			@Param("sesionId") long sesionId,
			@Param("responsable") Responsable responsable);

	@Override
	@Query("""
			SELECT o FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.personaId = :personaId
			   AND o.deletedAt IS NULL
			 ORDER BY o.devengadaEn DESC
			""")
	List<Obligacion> findDeLaPersona(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId);

	/**
	 * RF-M21-001. Nativo por la paginacion con {@code LIMIT/OFFSET} y por el {@code NOT EXISTS},
	 * que es lo que deja afuera las que ya estan vivas en otro lote sin volver a consultar fila por
	 * fila.
	 *
	 * <p>El {@code NOT EXISTS} espeja la columna generada {@code ocupa_marca} de V56 y no la usa
	 * directamente: la marca vale 1 o NULL, y un predicado sobre ella se leeria peor que los dos
	 * estados que la definen. Si algun dia divergen, el unique sigue siendo el que manda.
	 */
	@Override
	@Query(value = """
			SELECT o.* FROM obligacion o
			 WHERE o.organization_id = :organizationId
			   AND o.consultorio_id = :consultorioId
			   AND o.financiador_id = :financiadorId
			   AND o.responsable = 'FINANCIADOR'
			   AND o.estado IN ('PENDIENTE', 'PARCIAL')
			   AND o.saldo > 0
			   AND o.deleted_at IS NULL
			   AND (:desde IS NULL OR DATE(o.devengada_en) >= :desde)
			   AND (:hasta IS NULL OR DATE(o.devengada_en) <= :hasta)
			   AND NOT EXISTS (SELECT 1 FROM presentacion_item i
			                    WHERE i.obligacion_id = o.id
			                      AND i.estado IN ('INCLUIDO', 'ACEPTADO'))
			 ORDER BY o.devengada_en ASC, o.id ASC
			 LIMIT :limite OFFSET :desplazamiento
			""", nativeQuery = true)
	List<Obligacion> findElegiblesParaPresentar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("financiadorId") long financiadorId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			@Param("limite") int limite,
			@Param("desplazamiento") int desplazamiento);
}
