package com.akine.person.spi;

import java.time.LocalDate;

/**
 * La elegibilidad administrativa de M17 (RF-M17-007) para otros modulos.
 *
 * <p>Es la misma regla que publica {@code GET /personas/{id}/elegibilidad}
 * ({@code consultarElegibilidadAdministrativa}): que exige el convenio VIVO de la sede para esa
 * cobertura y practica, y si el paciente lo tiene. Nace con su primer consumidor, la recepcion de
 * {@code scheduling} (AKINE E-4).
 *
 * <p><b>Solo lectura y no autoriza nada.</b> No persiste ni consume (RN-M17-001) y no afirma que la
 * prestacion sea facturable (RN-M08-004). Quien llama ya resolvio pertenencia y permiso con su
 * propio criterio. Una persona o cobertura que no es de la organizacion responde <b>no elegible
 * con motivo</b>, nunca una excepcion: una costura entre modulos no es el lugar para un 404.
 */
public interface ElegibilidadAdministrativaDirectory {

	VeredictoDeElegibilidad evaluar(
			long organizationId, long consultorioId, long personaId, long coberturaId,
			long practicaId, LocalDate fecha);
}
