package com.akine.resource.application;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Alta de un bloque recurrente de disponibilidad de un profesional en una sede (RF-M05-003).
 *
 * <p>El profesional y la sede NO viajan aca: salen de la ruta y del contexto ya validado, igual
 * que en {@code EspacioAltaCommand}. Aceptarlos en el cuerpo permitiria que el cuerpo y la URL
 * dijeran cosas distintas, y alguna capa tendria que elegir cual gana.
 *
 * <h2>Los dos ejes temporales, que se confunden todo el tiempo</h2>
 *
 * <p>{@code diaSemana} + {@code horaDesde}/{@code horaHasta} son la RECURRENCIA: "los martes de
 * 09 a 13". {@code vigenciaDesde}/{@code vigenciaHasta} son la VENTANA en la que esa recurrencia
 * rige: "desde marzo y hasta que vuelva de la licencia". Son ortogonales y las dos hacen falta.
 *
 * @param diaSemana     ISO-8601: lunes = 1 .. domingo = 7
 * @param horaDesde     hora local de inicio, en la zona de la sede ({@code consultorio.timezone}).
 *                      Nunca un instante UTC: un "lunes 09:00" tiene que seguir siendo 09:00
 *                      despues de un cambio de huso o de horario de verano (diseno §1)
 * @param horaHasta     hora local de fin, <b>EXCLUSIVA</b>. Admite
 *                      {@code IntervaloLocal.FIN_DE_DIA} para un bloque que llega a la
 *                      medianoche; nunca cruza al dia siguiente
 * @param vigenciaDesde primer dia en que el bloque rige. {@code null} = hoy en la zona de la
 *                      sede, que es lo que quiere decir el administrador que carga el horario
 *                      del profesional que ya esta atendiendo
 * @param vigenciaHasta primer dia en que el bloque ya NO rige (<b>EXCLUSIVA</b>). {@code null} =
 *                      sin fin previsto, que es el caso normal
 */
public record BloqueAltaCommand(
		int diaSemana,
		LocalTime horaDesde,
		LocalTime horaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta) {
}
