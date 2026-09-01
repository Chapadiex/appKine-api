package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.SesionNumerador;
import com.akine.encounter.domain.port.SesionNumeradorPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Asignacion atomica del correlativo de sesion.
 *
 * <p>Las dos consultas son nativas a proposito: son las que deciden la correctitud y tienen que
 * estar a la vista de quien lee la clase, no escondidas detras de metodos derivados del nombre.
 */
public interface SesionNumeradorRepository
		extends JpaRepository<SesionNumerador, Long>, SesionNumeradorPort {

	/**
	 * Crea la fila si no existe. <b>Sin lanzar nunca.</b>
	 *
	 * <p>Leer y despues insertar produce un DEADLOCK entre los primeros N cierres concurrentes de
	 * una misma historia clinica, y envolverlo en un try/catch no alcanza porque atrapar una
	 * excepcion de persistencia no des-marca la transaccion. Es la leccion de AKINE-05.02, ya
	 * pagada dos veces en este proyecto.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO sesion_numerador (organization_id, historia_clinica_id, ultimo_numero,
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
	 * <p><b>No hay lectura previa</b>, y esa es toda la diferencia con {@code SELECT MAX(numero) + 1}:
	 * ese deja una ventana entre leer y escribir, y dos cierres concurrentes de dos sesiones del
	 * mismo paciente se llevan el mismo numero.
	 */
	@Modifying
	@Query(value = """
			UPDATE sesion_numerador
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
			SELECT ultimo_numero FROM sesion_numerador
			 WHERE organization_id = :organizationId
			   AND historia_clinica_id = :historiaClinicaId
			""", nativeQuery = true)
	@Override
	Integer leerUltimo(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId);
}
