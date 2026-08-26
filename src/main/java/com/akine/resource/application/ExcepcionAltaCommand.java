package com.akine.resource.application;

import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Alta de un cierre o de una apertura puntual de disponibilidad (RF-M05-004).
 *
 * <p>La sede NO viaja aca: sale de la ruta y del contexto ya validado, igual que en
 * {@link BloqueAltaCommand}. El profesional SI viaja, y es la diferencia importante: una
 * excepcion puede ser de un profesional o de la SEDE ENTERA, y el alcance es un dato del pedido,
 * no de la ruta.
 *
 * @param membershipId profesional al que se le carga la excepcion, o <b>{@code null} para la
 *                     SEDE ENTERA</b>. {@code null} significa alcance y no un dato faltante:
 *                     misma convencion que {@code consultorio_id} nulo en {@code membership}
 *                     (V10). Una excepcion de sede afecta a TODOS los profesionales
 * @param tipo         {@code CIERRE} recorta disponibilidad; {@code APERTURA} la habilita donde
 *                     no la habia. La apertura no es el caso raro: es como un centro declara que
 *                     atiende un feriado o que suma una banda un sabado puntual
 * @param motivo       lista cerrada, para que un reporte pueda agrupar sin normalizar despues
 * @param fechaDesde   primer dia cubierto
 * @param fechaHasta   primer dia YA NO cubierto (<b>EXCLUSIVO</b>). Un cierre de un solo dia se
 *                     carga como {@code [D, D+1)}: es el criterio de toda la etapa y el error
 *                     mas facil de cometer al escribir el cliente
 * @param horaDesde    hora local de inicio, o {@code null} para DIA COMPLETO
 * @param horaHasta    hora local de fin, EXCLUSIVA, o {@code null} para DIA COMPLETO. Viene
 *                     junto con {@code horaDesde} o no viene ninguna: el CHECK de V23 lo exige
 * @param feriadoId    feriado que motivo la excepcion, para trazar el origen cuando la carga
 *                     nace del calendario nacional. {@code null} si no
 * @param notes        texto libre de hasta 280 caracteres. Nunca informacion clinica
 */
public record ExcepcionAltaCommand(
		Long membershipId,
		TipoExcepcion tipo,
		MotivoExcepcion motivo,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		LocalTime horaDesde,
		LocalTime horaHasta,
		Long feriadoId,
		String notes) {
}
