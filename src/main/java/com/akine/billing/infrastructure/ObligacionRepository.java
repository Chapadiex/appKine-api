package com.akine.billing.infrastructure;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================
	//
	// Se calculan al leer. NO hay ninguna tabla de resumen economico, y la razon es la
	// misma que sostiene el resto del repositorio: una segunda copia se desincroniza el
	// dia que alguien escribe por otro camino. Aca ese dia esta anunciado —el devengado
	// de financiador se va a recablear— y con un agregado materializado el tablero no se
	// romperia: mentiria.
	//
	// Las tres suman DEUDA (M18). La deuda no es el cobro (M19) ni la caja (M20), y el
	// reporte no publica ningun total que las junte.

	/**
	 * Producido del periodo: lo que se devengo y sigue valiendo.
	 *
	 * <p>Excluye {@code ANULADA} y la baja logica. Lo anulado no desaparece del reporte —seria
	 * inauditable— sino que va en su propio indicador: ver {@link #sumarAnuladoEnElReporte}.
	 */
	@Query("""
			SELECT SUM(o.importeOriginal) FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.consultorioId = :consultorioId
			   AND o.estado <> com.akine.billing.domain.EstadoObligacion.ANULADA
			   AND o.deletedAt IS NULL
			   AND o.devengadaEn >= :desde
			   AND o.devengadaEn < :hasta
			""")
	BigDecimal sumarDevengadoEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Lo devengado y despues anulado, aparte.
	 *
	 * <p>Ni suma al producido ni desaparece. Es lo que responde el caso de QA "correcciones
	 * posteriores": alguien que ve el mes pasado con tres anulaciones entiende por que el total
	 * bajo, en vez de sospechar del reporte.
	 */
	@Query("""
			SELECT SUM(o.importeOriginal) FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.consultorioId = :consultorioId
			   AND o.estado = com.akine.billing.domain.EstadoObligacion.ANULADA
			   AND o.devengadaEn >= :desde
			   AND o.devengadaEn < :hasta
			""")
	BigDecimal sumarAnuladoEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Deuda viva <b>hoy</b>, no a la fecha de corte del reporte.
	 *
	 * <p>Y por eso no recibe el periodo. {@code saldo} es un derivado materializado que refleja el
	 * estado actual; reconstruirlo a una fecha pasada exigiria restar las imputaciones posteriores,
	 * que es una segunda formula de la misma cosa. Va declarado en el {@code criterioDeFecha} del
	 * indicador: <b>un numero honesto y explicado vale mas que uno exacto y silencioso.</b>
	 */
	@Query("""
			SELECT SUM(o.saldo) FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.consultorioId = :consultorioId
			   AND o.estado IN (com.akine.billing.domain.EstadoObligacion.PENDIENTE,
			                    com.akine.billing.domain.EstadoObligacion.PARCIAL)
			   AND o.deletedAt IS NULL
			""")
	BigDecimal sumarSaldoVigenteEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);

	/**
	 * Lo prestado a un financiador en el periodo (RF-M23-005), por financiador.
	 *
	 * <p><b>Hoy devuelve lista vacia en cualquier despliegue real</b>, y no es un defecto de la
	 * consulta: no existe ninguna obligacion con {@code responsable = FINANCIADOR} porque el
	 * devengado nunca se recableo contra convenios. Es la misma reserva declarada en el design
	 * challenge de AKINE-07.04, y el reporte la emite como advertencia en vez de mostrar un cero
	 * mudo.
	 */
	@Query(value = """
			SELECT o.financiador_id AS financiador, SUM(o.importe_original) AS total
			  FROM obligacion o
			 WHERE o.organization_id = :organizationId
			   AND o.consultorio_id = :consultorioId
			   AND o.responsable = 'FINANCIADOR'
			   AND o.estado <> 'ANULADA'
			   AND o.deleted_at IS NULL
			   AND o.devengada_en >= :desde
			   AND o.devengada_en < :hasta
			 GROUP BY o.financiador_id
			 LIMIT :limite
			""", nativeQuery = true)
	List<Object[]> sumarPrestadoPorFinanciadorEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("limite") int limite);
}
