package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoNumerador;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoNumeradorPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Asignacion atomica del correlativo del Caso dentro de la Historia Clinica.
 *
 * <p>Las tres consultas son nativas a proposito: son las que deciden la correctitud y tienen que
 * estar a la vista de quien lee la clase, no escondidas detras de metodos derivados del nombre.
 * Es textualmente el patron de {@code SesionNumeradorRepository} (06.05).
 */
public interface CasoNumeradorRepository
		extends JpaRepository<CasoNumerador, Long>, CasoNumeradorPort {

	/**
	 * Crea la fila si no existe. <b>Sin lanzar nunca.</b>
	 *
	 * <p>Leer y despues insertar produce un DEADLOCK entre las primeras N altas concurrentes de la
	 * misma historia, y envolverlo en un try/catch no alcanza porque atrapar una excepcion de
	 * persistencia no des-marca la transaccion: Spring lanza {@code UnexpectedRollbackException} al
	 * commitear. Ya se pago cuatro veces en este repositorio.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO caso_numerador (organization_id, historia_clinica_id, ultimo_numero,
			                            created_at, updated_at)
			VALUES (:organizationId, :historiaClinicaId, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId);

	/**
	 * Incrementa el contador. El {@code UPDATE} toma un lock exclusivo de fila y serializa.
	 *
	 * <p><b>No hay lectura previa</b>, y esa es toda la diferencia con {@code SELECT MAX + 1}: ese
	 * deja una ventana entre leer y escribir, y dos administrativos abriendo un caso para el mismo
	 * paciente al mismo tiempo se llevan el mismo numero.
	 */
	@Modifying
	@Query(value = """
			UPDATE caso_numerador
			   SET ultimo_numero = ultimo_numero + 1
			 WHERE organization_id = :organizationId
			   AND historia_clinica_id = :historiaClinicaId
			""", nativeQuery = true)
	@Override
	void incrementar(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId);

	/**
	 * Lee el numero recien asignado.
	 *
	 * <p>Se llama DESPUES de {@link #incrementar}, en la misma transaccion, asi que lee su propia
	 * escritura y el lock de fila sigue tomado: ninguna otra transaccion puede haber incrementado
	 * en el medio.
	 */
	@Query(value = """
			SELECT ultimo_numero FROM caso_numerador
			 WHERE organization_id = :organizationId
			   AND historia_clinica_id = :historiaClinicaId
			""", nativeQuery = true)
	@Override
	Integer leerUltimo(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId);
}
