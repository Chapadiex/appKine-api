package com.akine.encounter.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * El hecho de que una atencion se cerro, para quien tenga que reaccionar.
 *
 * <p>Lleva lo minimo que un consumidor necesita sin obligarlo a leer la sesion: quien, donde, que
 * se presto y si el paciente vino. <b>No lleva nada clinico</b> —ni el motivo, ni la evaluacion, ni
 * la nota— porque quien reacciona a un cierre hoy es el modulo economico, y la deuda no necesita
 * saber que le duele al paciente. Agregarlo "por si acaso" filtraria datos clinicos a un modulo que
 * no tiene permiso para verlos.
 *
 * <p><b>Se agranda cuando a un consumidor le falta un dato; no se le abre {@code domain}.</b> Es
 * lo que hizo AKINE-04.05 con {@link #cerradaPorCuentaId}: el consumo de autorizaciones tiene que
 * dejar asentado quien produjo el hecho, y la alternativa —que {@code person} leyera la sesion—
 * habria significado importar {@code encounter.domain} desde otro modulo. El criterio de arriba
 * sigue valiendo: se agrega lo que el consumidor NECESITA, nunca "por si acaso", y nada clinico.
 *
 * @param asistio            {@code false} si el paciente no vino. Decide si hubo prestacion
 * @param cerradaPorCuentaId cuenta que cerro la atencion. Es dato de AUTORIA del hecho, no
 *                           clinico: dice quien apreto el boton, no que escribio
 * @param practicasRealizadas ids de las practicas de M06 efectivamente aplicadas en la atencion
 *                           (AKINE-06.04). <b>Vacio significa "no se sabe", NO "ninguna"</b>: ver
 *                           abajo
 * @param casoId             caso clinico de la sesion, o {@code null} si la sesion no tiene caso
 *                           (AKINE C-4). Lo necesita el consumo de autorizaciones para no gastar
 *                           una autorizacion atada a otro caso (RF-M17-007). Es un id, no dato
 *                           clinico
 */
public record SesionCerrada(
		long sesionId,
		long organizationId,
		long consultorioId,
		long personaId,
		long ofertaId,
		int numeroSesion,
		boolean asistio,
		Instant cerradaEn,
		Long cerradaPorCuentaId,
		BigDecimal precioDeLaOferta,
		String moneda,
		Set<Long> practicasRealizadas,
		Long casoId) {

	/**
	 * <b>AKINE-06.04 agrega {@code practicasRealizadas}, y es lo que cierra el defecto que 04.05
	 * dejo declarado.</b>
	 *
	 * <p>Hasta 06.04 el consumo de autorizaciones elegia "la que vence antes" sin mirar la
	 * practica, porque no habia forma de saber que se aplico: <b>podia gastar la autorizacion
	 * equivocada</b> —una unidad de fonoaudiologia por una sesion de kinesiologia—, comiendole al
	 * paciente unidades que iba a necesitar y presentandole al financiador algo que no ocurrio.
	 *
	 * <p><b>No es dato clinico</b> y por eso puede viajar por aca: un id de practica dice que
	 * prestacion se facturo, no que le duele al paciente. Es el mismo criterio con el que
	 * {@link #ofertaId} ya viajaba.
	 *
	 * <p><b>Vacio significa "no se sabe", no "ninguna", y la diferencia es la etapa entera.</b>
	 * Son vacias <b>todas</b> las sesiones anteriores a 06.04 y tambien las de ofertas que no
	 * registran practicas —una consulta, una evaluacion inicial—. Un consumidor que tratara el
	 * vacio como "ninguna practica" dejaria de consumir autorizaciones en todo el sistema, que es
	 * peor que el defecto que esta etapa corrige. Ante el vacio se conserva el comportamiento
	 * anterior.
	 */
	public SesionCerrada {
		practicasRealizadas = practicasRealizadas == null ? Set.of() : Set.copyOf(practicasRealizadas);
	}

	/** Sin caso: la forma anterior a AKINE C-4. */
	@SuppressWarnings("java:S107")
	public SesionCerrada(
			long sesionId,
			long organizationId,
			long consultorioId,
			long personaId,
			long ofertaId,
			int numeroSesion,
			boolean asistio,
			Instant cerradaEn,
			Long cerradaPorCuentaId,
			BigDecimal precioDeLaOferta,
			String moneda,
			Set<Long> practicasRealizadas) {
		this(sesionId, organizationId, consultorioId, personaId, ofertaId, numeroSesion, asistio,
				cerradaEn, cerradaPorCuentaId, precioDeLaOferta, moneda, practicasRealizadas, null);
	}
}
