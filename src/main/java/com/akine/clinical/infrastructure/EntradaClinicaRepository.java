package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link EntradaClinica}.
 *
 * <p>Implementa el puerto de {@code domain} en vez de exponerse, igual que
 * {@link HistoriaClinicaRepository}: la capa de aplicacion no sabe que existe Spring Data.
 *
 * <p><b>No hay ni un metodo que resuelva por id pelado.</b> Toda consulta lleva
 * {@code organizationId}. En un modulo clinico un {@code findById} heredado y usado por descuido
 * no es una fuga de tenant cualquiera: es la historia clinica de otro centro.
 *
 * <p>El listado ordena por {@code ocurrioEn} descendente con desempate por id, mismo criterio que
 * {@link AntecedenteClinicoRepository}: dos entradas cargadas en la misma transaccion comparten
 * instante hasta el microsegundo, y sin el desempate el orden entre ellas seria el que quiera
 * MySQL —o sea, distinto en cada corrida y distinto entre paginas—.
 */
public interface EntradaClinicaRepository
		extends JpaRepository<EntradaClinica, Long>, EntradaClinicaRepositoryPort {

	@Override
	Optional<EntradaClinica> findByIdAndOrganizationId(Long id, Long organizationId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es la MISMA consulta derivada que la de arriba y lo unico que la distingue es el
	 * {@code OPTIMISTIC_FORCE_INCREMENT}. El nombre es derivable a proposito y no lleva
	 * {@code @Query}: Spring Data ignora el texto entre {@code find} y {@code By}, asi que
	 * {@code WithLock} documenta el metodo sin cambiar la consulta y sigue validandose contra el
	 * esquema real. Mismo patron que {@code offering.infrastructure.OfertaRepository}, que es
	 * donde esta leccion se pago.
	 */
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<EntradaClinica> findWithLockByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT e FROM EntradaClinica e
			 WHERE e.organizationId = :organizationId
			   AND e.historiaClinicaId = :historiaClinicaId
			   AND (:soloVigentes = false OR e.active = true)
			 ORDER BY e.ocurrioEn DESC, e.id DESC
			""")
	List<EntradaClinica> buscarDeHistoria(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("soloVigentes") boolean soloVigentes);
}
