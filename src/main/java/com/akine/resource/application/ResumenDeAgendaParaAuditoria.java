package com.akine.resource.application;

import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.DisponibilidadExcepcion;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * El {@code antes} y el {@code despues} legibles que un bloque o una excepcion dejan en los
 * {@code details} de su auditoria (historial de agenda por profesional, AKINE-agenda-historial).
 *
 * <p>Se escriben al auditar, con la fila entera, porque la edicion solo anota los campos que cambiaron
 * y el historial tiene que poder decir "Lun 08:00–14:00 → Lun 09:00–14:00" sin reconstruir el estado
 * desde eventos anteriores. Sin datos de pacientes: horario, fechas y motivo.
 */
final class ResumenDeAgendaParaAuditoria {

	static final String ANTES = "antes";
	static final String DESPUES = "despues";

	private static final String[] DIAS = {"Lun", "Mar", "Mie", "Jue", "Vie", "Sab", "Dom"};
	private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

	private ResumenDeAgendaParaAuditoria() {
	}

	/** "Lun 08:00–14:00, desde 01/11/2026 hasta 30/11/2026". */
	static String de(BloqueDisponibilidad bloque) {
		StringBuilder texto = new StringBuilder()
				.append(dia(bloque.getDiaSemana())).append(' ')
				.append(rango(bloque.getHoraDesde(), bloque.getHoraHasta()));
		if (bloque.getVigenciaDesde() != null) {
			texto.append(", desde ").append(fecha(bloque.getVigenciaDesde()));
		}
		if (bloque.getVigenciaHasta() != null) {
			texto.append(" hasta ").append(fecha(bloque.getVigenciaHasta()));
		}
		return texto.toString();
	}

	/** "Cierre por licencia, 10/11/2026 al 12/11/2026, 09:00–12:00" o "..., dia completo". */
	static String de(DisponibilidadExcepcion excepcion) {
		String tipo = excepcion.getTipo() == null ? "Excepcion" : capital(excepcion.getTipo().name());
		String motivo = excepcion.getMotivo() == null ? "" : " por " + excepcion.getMotivo().name().toLowerCase(Locale.ROOT);
		String fechas = excepcion.getFechaHasta() == null || excepcion.getFechaHasta().equals(excepcion.getFechaDesde())
				? fecha(excepcion.getFechaDesde())
				: fecha(excepcion.getFechaDesde()) + " al " + fecha(excepcion.getFechaHasta());
		String horario = excepcion.getHoraDesde() == null
				? "dia completo"
				: rango(excepcion.getHoraDesde(), excepcion.getHoraHasta());
		return tipo + motivo + ", " + fechas + ", " + horario;
	}

	/** Suma {@code antes} y/o {@code despues} a los detalles; un {@code null} no se escribe. */
	static Map<String, String> con(Map<String, String> detalles, String antes, String despues) {
		if (antes != null) {
			detalles.put(ANTES, antes);
		}
		if (despues != null) {
			detalles.put(DESPUES, despues);
		}
		return detalles;
	}

	private static String dia(int diaSemana) {
		return diaSemana >= 1 && diaSemana <= 7 ? DIAS[diaSemana - 1] : "Dia " + diaSemana;
	}

	private static String rango(LocalTime desde, LocalTime hasta) {
		return hora(desde) + "–" + hora(hasta);
	}

	private static String hora(LocalTime hora) {
		return hora == null ? "?" : HORA.format(hora);
	}

	private static String fecha(LocalDate fecha) {
		return fecha == null ? "?" : FECHA.format(fecha);
	}

	private static String capital(String nombre) {
		String minuscula = nombre.toLowerCase(Locale.ROOT);
		return Character.toUpperCase(minuscula.charAt(0)) + minuscula.substring(1);
	}
}
