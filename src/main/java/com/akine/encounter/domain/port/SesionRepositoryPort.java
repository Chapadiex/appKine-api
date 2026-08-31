package com.akine.encounter.domain.port;

import com.akine.encounter.domain.Sesion;

import java.util.Optional;

/**
 * Persistencia de sesiones.
 *
 * <p>Vive en {@code domain} porque {@code application} consume puertos y nunca repositorios de
 * {@code infrastructure}: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 */
public interface SesionRepositoryPort {

	Sesion save(Sesion sesion);

	Optional<Sesion> findByIdInScope(long organizationId, long consultorioId, long sesionId);

	/**
	 * La sesion viva de ese turno, si ya se inicio.
	 *
	 * <p>Es lo que hace idempotente el doble inicio: RN-M14-001 dice que un turno produce como
	 * mucho una sesion, y el {@code uk_sesion_turno} de V33 lo hace cumplir del lado del motor.
	 * Esta consulta es la que permite devolver la sesion existente en vez de chocar contra el
	 * unique y contestar un 409 que para el usuario no significa nada — apreto dos veces.
	 */
	Optional<Sesion> findVivaPorTurno(long organizationId, long turnoId);

	/**
	 * La sesion anterior del mismo paciente que tenga evaluacion cargada.
	 *
	 * <p>Es lo que permite comparar: "la vez pasada tenia 7". Se filtra por {@code evaluadaEn}
	 * y no simplemente por la anterior en el tiempo, porque una sesion que se abrio y no se
	 * evaluo no tiene nada contra que comparar y devolverla mostraria campos vacios donde el
	 * profesional espera un numero.
	 */
	Optional<Sesion> findPreviaEvaluada(
			long organizationId, long historiaClinicaId, java.time.Instant antesDe);
}
