package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link CasoClinico}.
 *
 * <p>Implementa el puerto de {@code domain} en vez de exponerse, igual que
 * {@link EntradaClinicaRepository}: la capa de aplicacion no sabe que existe Spring Data.
 *
 * <p><b>No hay ni un metodo que resuelva por id pelado.</b> Toda consulta lleva
 * {@code organizationId}. En un modulo clinico un {@code findById} heredado y usado por descuido
 * no es una fuga de tenant cualquiera: es la historia clinica de otro centro.
 *
 * <p>Los dos listados desempatan por {@code id DESC} ademas de por instante, mismo criterio que el
 * resto del modulo: dos casos abiertos en la misma transaccion comparten instante hasta el
 * microsegundo, y sin el desempate el orden entre ellos seria el que quiera MySQL.
 */
public interface CasoClinicoRepository
		extends JpaRepository<CasoClinico, Long>, CasoClinicoRepositoryPort {

	@Override
	Optional<CasoClinico> findByIdAndOrganizationId(Long id, Long organizationId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es la MISMA consulta derivada que la de arriba y lo unico que la distingue es el
	 * {@code OPTIMISTIC_FORCE_INCREMENT}. El nombre es derivable a proposito y no lleva
	 * {@code @Query}: Spring Data ignora el texto entre {@code find} y {@code By}, asi que
	 * {@code WithLock} documenta el metodo sin cambiar la consulta y sigue validandose contra el
	 * esquema real. Mismo patron que {@link EntradaClinicaRepository}.
	 */
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<CasoClinico> findWithLockByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT c FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.historiaClinicaId = :historiaClinicaId
			   AND (:soloActivos = false OR c.estado = com.akine.clinical.domain.EstadoCaso.ACTIVO)
			 ORDER BY c.abiertoEn DESC, c.id DESC
			""")
	List<CasoClinico> buscarDeHistoria(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("soloActivos") boolean soloActivos);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Calza con {@code ix_caso_clinico_oferta}
	 * {@code (organization_id, historia_clinica_id, oferta_id, estado)}. No tiene tope: un paciente
	 * no acumula cientos de casos activos de la misma oferta, y si los acumulara, eso es
	 * justamente lo que quien recibe el 409 tiene que ver entero.
	 */
	@Override
	@Query("""
			SELECT c FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.historiaClinicaId = :historiaClinicaId
			   AND c.ofertaId = :ofertaId
			   AND c.estado = com.akine.clinical.domain.EstadoCaso.ACTIVO
			 ORDER BY c.abiertoEn DESC, c.id DESC
			""")
	List<CasoClinico> buscarActivosPorOferta(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("ofertaId") Long ofertaId);

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================
	//
	// Tres conteos, calculados al leer. La sede es `ofertaConsultorioId` y no una columna
	// `consultorioId`, que no existe: el Caso cuelga de la Historia Clinica, que es de la
	// ORGANIZACION (DP-03), asi que su sede es la de la oferta que lo origino. Es la
	// columna que indexa `ix_caso_clinico_sede_apertura` (V59), el primer indice no-unico
	// que esta tabla tiene.

	// G-1 (DP-15): con `recortar` en verdadero, los tres cuentan solo los casos donde alguna de
	// `memberships` integra o integro el equipo (`caso_profesional`, cualquier vigencia): es lo
	// que "le fue asignado" al profesional. Quien dejo el equipo trato al paciente, y la tabla no
	// borra la fila justamente por eso (V47, punto 5). EXISTS y no JOIN: un profesional que entro,
	// salio y volvio tiene dos filas, y un JOIN contaria el caso dos veces.

	/** Casos abiertos en el periodo. */
	@Query("""
			SELECT COUNT(c) FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.ofertaConsultorioId = :consultorioId
			   AND (:recortar = false OR EXISTS (
			        SELECT cp.id FROM CasoProfesional cp
			         WHERE cp.organizationId = c.organizationId
			           AND cp.casoId = c.id
			           AND cp.profesionalMembershipId IN :memberships))
			   AND c.abiertoEn >= :desde
			   AND c.abiertoEn < :hasta
			""")
	long contarAbiertosEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") java.time.Instant desde,
			@Param("hasta") java.time.Instant hasta,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships);

	/**
	 * Casos cerrados en el periodo.
	 *
	 * <p>No es el complemento del anterior: un caso abierto en marzo y cerrado en septiembre
	 * cuenta en los dos reportes, en el indicador que corresponde a cada uno.
	 */
	@Query("""
			SELECT COUNT(c) FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.ofertaConsultorioId = :consultorioId
			   AND (:recortar = false OR EXISTS (
			        SELECT cp.id FROM CasoProfesional cp
			         WHERE cp.organizationId = c.organizationId
			           AND cp.casoId = c.id
			           AND cp.profesionalMembershipId IN :memberships))
			   AND c.cerradoEn >= :desde
			   AND c.cerradoEn < :hasta
			""")
	long contarCerradosEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") java.time.Instant desde,
			@Param("hasta") java.time.Instant hasta,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships);

	/**
	 * Casos activos <b>hoy</b>, no al dia de corte del reporte.
	 *
	 * <p>Y por eso no recibe el periodo. Reconstruir el estado a una fecha pasada exigiria
	 * recorrer {@code caso_evento} hacia atras, que es una segunda formula de la misma cosa: el
	 * dia que las dos divergieran nadie sabria cual creer. Va declarado en el
	 * {@code criterioDeFecha} del indicador.
	 */
	@Query("""
			SELECT COUNT(c) FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.ofertaConsultorioId = :consultorioId
			   AND (:recortar = false OR EXISTS (
			        SELECT cp.id FROM CasoProfesional cp
			         WHERE cp.organizationId = c.organizationId
			           AND cp.casoId = c.id
			           AND cp.profesionalMembershipId IN :memberships))
			   AND c.estado = com.akine.clinical.domain.EstadoCaso.ACTIVO
			""")
	long contarActivosEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships);
}
