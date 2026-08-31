package com.akine.clinical.spi;

import java.time.Instant;

/**
 * Lo que otro modulo necesita saber de una Historia Clinica, sin poder tocar su entity.
 *
 * <p>Es el "identificador de HC estable" que la etapa AKINE-04.01 declara como contexto para las
 * siguientes: con {@link #id} alcanza para que una Sesion (06.01), un Caso (04.03) o el timeline
 * (04.02) se cuelguen de la historia correcta sin leer una sola fila de {@code historia_clinica}.
 *
 * <p><b>No viaja el resumen ni ningun antecedente.</b> El contenido clinico se lee con permiso
 * {@code hc:read} y acceso justificado, por el servicio de aplicacion y con auditoria; un record
 * que lo trajera de arrastre convertiria a cualquier consumidor del spi en una via de lectura
 * clinica sin auditar. Lo que viaja es la existencia y la vigencia, que es lo que un modulo
 * necesita para decidir a que colgarse.
 */
public record HistoriaClinicaSnapshot(
		long id,
		long organizationId,
		long personaId,
		Instant abiertaEn,
		boolean vigente,
		long version) {
}
