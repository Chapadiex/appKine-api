package com.akine.person.spi;

import java.time.LocalDate;
import java.util.Set;

/**
 * El pedido de consumir unidades autorizadas a raiz de una atencion realizada (RF-M17-004).
 *
 * <p>Lleva lo minimo que {@code person} necesita para descontar sin obligarlo a leer la sesion, y
 * <b>nada clinico</b>: ni el motivo, ni la evaluacion, ni la nota. Quien consume una autorizacion
 * es el circuito administrativo, y el saldo de un financiador no necesita saber que le duele al
 * paciente. Es el mismo criterio con el que {@code encounter.spi.SesionCerrada} se nego a llevarlo.
 *
 * @param fecha          dia LOCAL de la sede contra el que se evalua vigencia y saldo. Se calcula
 *                       afuera, con la zona IANA del consultorio: el instante UTC del cierre y el
 *                       dia en que la autorizacion vence no estan en la misma escala, y resolverlo
 *                       en UTC corre el dia para media Argentina despues de las 21:00
 * @param cantidad       unidades a descontar. Una por sesion hoy; el parametro existe porque una
 *                       prestacion que vale dos sesiones es una configuracion de oferta que ya se
 *                       discute en M27, y cambiarlo despues seria cambiar el contrato
 * @param actorCuentaId  quien cerro la sesion, para que el movimiento diga quien lo produjo
 * @param practicasRealizadas ids de las practicas de M06 efectivamente aplicadas (AKINE-06.04).
 *                       <b>Acota contra que autorizaciones se puede imputar.</b> Vacio significa
 *                       "no se sabe", NO "ninguna": ver abajo
 * @param autorizacionesDeOtroCaso autorizaciones atadas a planes de OTRO caso de la misma historia
 *                       y no a uno del caso de la sesion (AKINE C-4, RF-M17-007 "no reutilizar
 *                       autorizacion de otro Caso"). Se excluyen del consumo. Vacio si la sesion
 *                       no tiene caso: ver docs/diseno/AKINE-C-4-consumo.md §5
 */
public record ConsumoPorSesion(
		long organizationId,
		long personaId,
		Long consultorioId,
		long sesionId,
		LocalDate fecha,
		int cantidad,
		Long actorCuentaId,
		Set<Long> practicasRealizadas,
		Set<Long> autorizacionesDeOtroCaso) {

	/**
	 * <b>AKINE-06.04 agrega {@code practicasRealizadas} y con eso cierra el defecto que 04.05 dejo
	 * declarado por escrito.</b>
	 *
	 * <p>La autorizacion de M17 se otorga por {@code practica_id}, y hasta 06.04 este consumo
	 * elegia "la que vence antes" sin mirarla, porque no habia forma de saber que se aplico en la
	 * sesion. Podia gastar una unidad de fonoaudiologia por una sesion de kinesiologia: le come al
	 * paciente unidades que si iba a necesitar, deja intacta la autorizacion que correspondia, y
	 * frente al financiador es una declaracion falsa.
	 *
	 * <p><b>Vacio significa "no se sabe", no "ninguna".</b> Son vacias todas las sesiones
	 * anteriores a 06.04 y las de ofertas que no registran practicas. Ante el vacio se conserva el
	 * comportamiento anterior; filtrar igual apagaria el consumo de autorizaciones en todo el
	 * sistema.
	 *
	 * <p>Sigue sin viajar <b>nada clinico</b>: un id de practica dice que prestacion se facturo,
	 * no que le duele al paciente. Es el mismo criterio con el que este record se nego a llevar el
	 * motivo y la evaluacion.
	 */
	public ConsumoPorSesion {
		practicasRealizadas = practicasRealizadas == null ? Set.of() : Set.copyOf(practicasRealizadas);
		autorizacionesDeOtroCaso = autorizacionesDeOtroCaso == null
				? Set.of() : Set.copyOf(autorizacionesDeOtroCaso);
	}

	/** Sin restriccion de caso: la forma de 06.04, para sesiones sin caso. */
	@SuppressWarnings("java:S107")
	public ConsumoPorSesion(
			long organizationId,
			long personaId,
			Long consultorioId,
			long sesionId,
			LocalDate fecha,
			int cantidad,
			Long actorCuentaId,
			Set<Long> practicasRealizadas) {
		this(organizationId, personaId, consultorioId, sesionId, fecha, cantidad, actorCuentaId,
				practicasRealizadas, Set.of());
	}
}
