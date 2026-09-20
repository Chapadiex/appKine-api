package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.PlanNumerador;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanNumeradorPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Asignacion atomica del correlativo del Plan dentro del Caso Clinico.
 *
 * <p>Las tres consultas son nativas a proposito: son las que deciden la correctitud y tienen que
 * estar a la vista de quien lee la clase, no escondidas detras de metodos derivados del nombre. Es
 * textualmente el patron de {@link CasoNumeradorRepository} (04.03) y de
 * {@code SesionNumeradorRepository} (06.05).
 */
public interface PlanNumeradorRepository
		extends JpaRepository<PlanNumerador, Long>, PlanNumeradorPort {

	/**
	 * Crea la fila si no existe. <b>Sin lanzar nunca.</b>
	 *
	 * <p>Leer y despues insertar produce un DEADLOCK entre las primeras N creaciones concurrentes de
	 * planes del mismo caso, y envolverlo en un try/catch no alcanza porque atrapar una excepcion de
	 * persistencia no des-marca la transaccion: Spring lanza {@code UnexpectedRollbackException} al
	 * commitear. Ya se pago cuatro veces en este repositorio.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO plan_numerador (organization_id, caso_clinico_id, ultimo_numero,
			                            created_at, updated_at)
			VALUES (:organizationId, :casoClinicoId, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("casoClinicoId") long casoClinicoId);

	/**
	 * Incrementa el contador. El {@code UPDATE} toma un lock exclusivo de fila y serializa.
	 *
	 * <p><b>No hay lectura previa</b>, y esa es toda la diferencia con {@code SELECT MAX + 1}: ese
	 * deja una ventana entre leer y escribir, y dos planes creados a la vez para el mismo caso se
	 * llevan el mismo numero — que aca no es cosmetico, porque {@code numero_plan} es el
	 * discriminador del unique de plan activo.
	 */
	@Modifying
	@Query(value = """
			UPDATE plan_numerador
			   SET ultimo_numero = ultimo_numero + 1
			 WHERE organization_id = :organizationId
			   AND caso_clinico_id = :casoClinicoId
			""", nativeQuery = true)
	@Override
	void incrementar(
			@Param("organizationId") long organizationId,
			@Param("casoClinicoId") long casoClinicoId);

	/**
	 * Lee el numero recien asignado.
	 *
	 * <p>Se llama DESPUES de {@link #incrementar}, en la misma transaccion, asi que lee su propia
	 * escritura y el lock de fila sigue tomado: ninguna otra transaccion puede haber incrementado en
	 * el medio. Y por eso la creacion del plan corre en {@code READ_COMMITTED}: bajo el
	 * {@code REPEATABLE READ} por defecto, InnoDB fija la foto en la primera lectura consistente
	 * —que ocurre ANTES del lock— y este SELECT podria leer el valor viejo.
	 */
	@Query(value = """
			SELECT ultimo_numero FROM plan_numerador
			 WHERE organization_id = :organizationId
			   AND caso_clinico_id = :casoClinicoId
			""", nativeQuery = true)
	@Override
	Integer leerUltimo(
			@Param("organizationId") long organizationId,
			@Param("casoClinicoId") long casoClinicoId);
}
