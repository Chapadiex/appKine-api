package com.akine.billing.domain.port;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistencia de obligaciones. Vive en {@code domain}: {@code application} consume puertos. */
public interface ObligacionRepositoryPort {

	Obligacion save(Obligacion obligacion);

	Optional<Obligacion> findByIdInScope(long organizationId, long consultorioId, long obligacionId);

	/**
	 * La obligacion ya devengada por esa prestacion, si existe.
	 *
	 * <p>Es la idempotencia de RN-M18-001: una prestacion genera su deuda una sola vez. Hace falta
	 * porque el cierre de sesion es idempotente por RN-M14-005 —cerrar dos veces devuelve lo
	 * mismo— pero el observador que devenga se ejecuta en el camino del cierre, y sin esta consulta
	 * un segundo cierre chocaria contra el unique de V36 en vez de no hacer nada.
	 */
	Optional<Obligacion> findPorPrestacion(long sesionId, Responsable responsable);

	/** La cuenta corriente de un paciente en la organizacion, de la mas reciente a la mas vieja. */
	List<Obligacion> findDeLaPersona(long organizationId, long personaId);

	/**
	 * Las prestaciones que se le pueden reclamar a un financiador en un periodo (RF-M21-001).
	 *
	 * <p>Filtra por {@code responsable = FINANCIADOR}, saldo positivo, estado cobrable y que no
	 * esten vivas en ningun lote —lo que deja afuera las ya presentadas sin tener que consultarlo
	 * despues, fila por fila—.
	 *
	 * <p><b>Hoy devuelve lista vacia en cualquier despliegue real</b>, y no es un defecto de la
	 * consulta: no existe ninguna obligacion con responsable {@code FINANCIADOR} porque el
	 * devengado nunca se recableo contra convenios. Es la reserva declarada en el design challenge
	 * de AKINE-07.04.
	 */
	List<Obligacion> findElegiblesParaPresentar(
			long organizationId, long consultorioId, long financiadorId,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento);
}
