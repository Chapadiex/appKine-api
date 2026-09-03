package com.akine.person.application;

import com.akine.person.domain.EstadoAutorizacion;

import java.time.LocalDate;

/**
 * Alta de una autorizacion (RF-M17-001).
 *
 * <p>{@code estadoInicial} admite PENDIENTE —se pidio y falta respuesta— o APROBADA —el
 * financiador ya la otorgo por telefono y el mostrador la carga resuelta—. Nulo vale PENDIENTE.
 * OBSERVADA y RECHAZADA no se pueden cargar de entrada: son la respuesta a un pedido.
 *
 * <p>{@code coberturaId} y {@code practicaId} son obligatorios. La primera porque una autorizacion
 * sin cobertura no significa nada —no hay financiador que la haya dado—; la segunda porque la
 * practica es el eje sobre el que se pacta con un financiador, el mismo que usa el arancel del
 * convenio en M16.
 */
public record AutorizacionAltaCommand(
		Long coberturaId,
		Long practicaId,
		Long ordenMedicaId,
		String numero,
		EstadoAutorizacion estadoInicial,
		Integer cantidadAutorizada,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String observaciones) {
}
