package com.akine.resource.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Traduce el limite de {@link IntervaloLocal#FIN_DE_DIA} entre el dominio y una columna
 * {@code TIME} que admite {@code '24:00:00'} (V23: {@code hora_hasta}, {@code hora_desde} de
 * {@code profesional_disponibilidad} y de {@code disponibilidad_excepcion}).
 *
 * <h2>Por que hace falta un converter y no alcanza con mapear LocalTime directo</h2>
 *
 * <p>{@link LocalTime} no puede representar las 24:00 —su maximo es 23:59:59.999999999— pero
 * MySQL SI acepta {@code '24:00:00'} en una columna {@code TIME}, y la migracion V23 lo permite
 * a proposito (un bloque nocturno se carga como dos filas separadas por medianoche; ver la
 * cabecera de V23). Mapear la columna como {@code LocalTime} sin converter hace que Hibernate le
 * pida al driver un {@code LocalTime} directamente, y esa conversion falla o desborda apenas
 * aparece la primera fila que llega hasta medianoche.
 *
 * <h2>Por que el tipo intermedio es {@link String} y no {@code java.sql.Time}</h2>
 *
 * <p>{@code java.sql.Time} esta atado a un instante de epoch: normaliza silenciosamente la hora
 * 24 a las 00:00 del dia siguiente, que es exactamente la ambiguedad que este converter existe
 * para evitar (medianoche de HOY, no medianoche de MAÑANA). Convertir contra {@link String} hace
 * que Hibernate use {@code ResultSet.getString}/{@code PreparedStatement.setString}, que mueven
 * el texto {@code "HH:mm:ss"} tal cual sin que el driver intente construir un tipo temporal —el
 * servidor de MySQL hace el cast implicito de texto a {@code TIME} en la asignacion.
 *
 * <p>Ida y vuelta de una hora normal (por ejemplo {@code 09:00:00}) no cambia: solo
 * {@code LocalTime.MAX} recibe el tratamiento especial.
 */
@Converter
public class HoraLocalConverter implements AttributeConverter<LocalTime, String> {

	private static final DateTimeFormatter FORMATO_DB = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final String FIN_DE_DIA_DB = "24:00:00";

	@Override
	public String convertToDatabaseColumn(LocalTime attribute) {
		if (attribute == null) {
			return null;
		}
		if (attribute.equals(IntervaloLocal.FIN_DE_DIA)) {
			return FIN_DE_DIA_DB;
		}
		return attribute.format(FORMATO_DB);
	}

	@Override
	public LocalTime convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		// MySQL puede devolver fraccion de segundos si la columna la tuviera; V23 no la define,
		// pero recortar a HH:mm:ss es defensivo y no cambia el caso normal.
		String normalizado = dbData.length() > 8 ? dbData.substring(0, 8) : dbData;
		if (FIN_DE_DIA_DB.equals(normalizado)) {
			return IntervaloLocal.FIN_DE_DIA;
		}
		return LocalTime.parse(normalizado);
	}
}
