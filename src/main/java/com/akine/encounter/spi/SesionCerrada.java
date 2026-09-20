package com.akine.encounter.spi;

import java.math.BigDecimal;
import java.time.Instant;

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
		String moneda) {
}
