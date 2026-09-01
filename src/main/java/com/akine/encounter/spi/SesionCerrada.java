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
 * @param asistio {@code false} si el paciente no vino. Es lo que decide si hubo prestacion
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
		BigDecimal precioDeLaOferta,
		String moneda) {
}
