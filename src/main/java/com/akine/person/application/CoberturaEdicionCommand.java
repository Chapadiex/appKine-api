package com.akine.person.application;

import java.time.LocalDate;

/**
 * Edicion de una cobertura (RF-M08-002) y cierre de su vigencia (RF-M08-003).
 *
 * <p><b>Lo que no esta en este record no se puede cambiar, y esa ausencia es la regla.</b> El tipo,
 * el plan y los nueve campos de la copia congelada son inmutables: cambiar de plan es OTRA
 * cobertura —se finaliza la vigente y se agrega la nueva— porque editarla en el lugar reescribiria
 * retroactivamente con que cobertura se atendio al paciente el mes pasado (RN-M08-003).
 *
 * <p>Cada campo nulo significa "no lo toques". La marca principal tampoco viaja aca: es un
 * invariante entre filas, se resuelve bajo el lock y tiene su propia operacion.
 *
 * @param expectedVersion version leida por el cliente. Una version vieja produce 409 en vez de
 *                        pisar el cambio ajeno en silencio
 */
public record CoberturaEdicionCommand(
		String numeroAfiliado,
		LocalDate credencialVigenciaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String observaciones,
		long expectedVersion) {
}
