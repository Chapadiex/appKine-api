package com.akine.billing.domain.port;

import com.akine.billing.domain.Obligacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistencia de obligaciones. Vive en {@code domain}: {@code application} consume puertos. */
public interface ObligacionRepositoryPort {

	Obligacion save(Obligacion obligacion);

	Optional<Obligacion> findByIdInScope(long organizationId, long consultorioId, long obligacionId);

	/**
	 * Las obligaciones vivas ya devengadas por esa prestacion, de cualquier responsable.
	 *
	 * <p>Es la idempotencia de RN-M18-001: una prestacion genera su deuda una sola vez. Hace falta
	 * porque el observador que devenga se ejecuta en el camino del cierre, y sin esta consulta un
	 * re-disparo chocaria contra el unique de V36 en vez de no hacer nada.
	 *
	 * <p><b>Desde AKINE F-4 mira todos los responsables, no solo el paciente.</b> Una prestacion
	 * cubierta devenga dos filas: si el re-disparo preguntara solo por el paciente, una sesion que
	 * devengo financiador y coseguro no veria nada que la frene y agregaria una particular encima.
	 */
	List<Obligacion> findDeLaSesion(long sesionId);

	/**
	 * La cuenta corriente <b>del paciente</b> en la organizacion (RF-M18-003): solo lo que debe el
	 * paciente, de la mas reciente a la mas vieja.
	 *
	 * <p><b>Excluye la parte del financiador, a proposito (AKINE F-4).</b> La persona de esas filas
	 * es el paciente atendido, pero quien debe es la obra social: la pantalla de cuenta corriente
	 * y el resumen del Paciente 360 suman este saldo, y con ellas adentro le dirian a alguien que
	 * debe la cuota de su obra social. La deuda del financiador se lee por M21 (bandeja y cuenta
	 * corriente del financiador).
	 */
	List<Obligacion> findDeLaPersona(long organizationId, long personaId);

	/**
	 * Las prestaciones que se le pueden reclamar a un financiador en un periodo (RF-M21-001).
	 *
	 * <p>Filtra por {@code responsable = FINANCIADOR}, saldo positivo, estado cobrable y que no
	 * esten vivas en ningun lote —lo que deja afuera las ya presentadas sin tener que consultarlo
	 * despues, fila por fila—.
	 *
	 * <p>Hasta AKINE F-4 devolvia lista vacia en cualquier despliegue real: nada devengaba
	 * obligaciones con responsable {@code FINANCIADOR}. Desde F-4 las devenga
	 * {@code ObligacionDevengador} al cerrar una sesion cubierta por un convenio.
	 */
	List<Obligacion> findElegiblesParaPresentar(
			long organizationId, long consultorioId, long financiadorId,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento);
}
