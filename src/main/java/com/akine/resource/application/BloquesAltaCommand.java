package com.akine.resource.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Alta de VARIOS bloques recurrentes de un profesional en una sede, con el mismo horario y la
 * misma vigencia: "de lunes a viernes de 09 a 13, desde marzo" (RF-M05-003).
 *
 * <p>Es el pedido que hace el formulario de carga rapida del horario semanal: antes, cargar la
 * semana de un profesional eran cinco altas, una por dia, y la cuarta podia fallar dejando tres.
 * El lote es <b>una sola transaccion</b>: o entran todos los dias o no entra ninguno, y el 409 de
 * solapamiento nombra el dia que lo produjo.
 *
 * <p>Cada dia se valida exactamente como un alta suelta ({@link BloqueAltaCommand}): coincidencia
 * exacta devuelve el bloque que ya existe sin duplicarlo, solapamiento es 409, y los chequeos
 * entre sedes son los mismos. No hay reglas nuevas: hay una transaccion que las agrupa.
 *
 * @param diasSemana    dias ISO-8601 (lunes = 1 .. domingo = 7), sin repetir y al menos uno
 * @param horaDesde     hora local de inicio, compartida por todos los dias
 * @param horaHasta     hora local de fin, EXCLUSIVA, compartida por todos los dias
 * @param vigenciaDesde primer dia en que rigen. {@code null} = hoy en la zona de la sede
 * @param vigenciaHasta primer dia en que ya NO rigen (EXCLUSIVA). {@code null} = sin fin previsto
 */
public record BloquesAltaCommand(
		Set<Integer> diasSemana,
		LocalTime horaDesde,
		LocalTime horaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta) {

	/** El alta de UN dia del lote, con el horario y la vigencia compartidos. */
	public BloqueAltaCommand paraElDia(int dia) {
		return new BloqueAltaCommand(dia, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta);
	}
}
