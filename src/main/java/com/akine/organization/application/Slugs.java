package com.akine.organization.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Derivacion del slug de una organizacion a partir de su nombre.
 *
 * <p>El slug es el identificador legible del tenant, unico GLOBAL, y se usa en URLs y en
 * soporte. Por eso no se puede renombrar: cambiarlo rompe enlaces y desorienta a quien
 * atiende un incidente.
 *
 * <p>Existe porque el alta por {@code spi} puede no traer slug —el registro self-service pide
 * el nombre del centro, no una cadena para URL— y {@code organization.slug} es NOT NULL. Sin
 * esto, el llamador tendria que inventar la regla, y la inventaria distinta cada modulo.
 *
 * <p>La unicidad se resuelve con un sufijo numerico y NO termina aca: el chequeo de
 * disponibilidad y el INSERT tienen una ventana entre medio, y quien la cierra de verdad es
 * {@code uk_organization_slug}. Este metodo evita el 409 evitable, no reemplaza la
 * restriccion.
 */
final class Slugs {

	/** Tope de intentos con sufijo antes de rendirse y dejar que decida la restriccion. */
	private static final int MAX_INTENTOS = 50;

	private static final int LARGO_MAXIMO = 64;

	private Slugs() {
		// Utilidad sin estado.
	}

	/**
	 * Convierte un nombre en un slug disponible.
	 *
	 * @param nombre    nombre de la organizacion
	 * @param ocupado   dice si un slug candidato ya esta tomado
	 * @return un slug no vacio, en minusculas, sin acentos y de a lo sumo 64 caracteres
	 */
	static String derivar(String nombre, Predicate<String> ocupado) {
		String base = normalizar(nombre);
		if (!ocupado.test(base)) {
			return base;
		}
		for (int sufijo = 2; sufijo < MAX_INTENTOS; sufijo++) {
			String candidato = recortar(base, String.valueOf(sufijo));
			if (!ocupado.test(candidato)) {
				return candidato;
			}
		}
		// Se agotaron los intentos legibles: se devuelve el ultimo y la restriccion unica
		// decide. Preferimos un 409 explicito antes que un slug ilegible generado al azar.
		return recortar(base, String.valueOf(MAX_INTENTOS));
	}

	/**
	 * Normaliza un slug provisto por el llamador o derivado de un nombre.
	 *
	 * <p>Los acentos se descomponen y se descartan en lugar de traducirse: "Kinesiologia
	 * Nunez" y "Kinesiologia Nuñez" tienen que producir slugs distintos solo si los nombres
	 * son distintos, no por como se tipeo la enie.
	 */
	static String normalizar(String valor) {
		String sinAcentos = Normalizer.normalize(valor, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "");
		String limpio = sinAcentos.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("(^-+)|(-+$)", "");
		if (limpio.isEmpty()) {
			// Un nombre escrito enteramente en un alfabeto no latino no deja nada utilizable.
			// Devolver vacio violaria el NOT NULL: se usa un prefijo estable y el sufijo
			// numerico resuelve la unicidad.
			limpio = "org";
		}
		return limpio.length() > LARGO_MAXIMO ? limpio.substring(0, LARGO_MAXIMO) : limpio;
	}

	private static String recortar(String base, String sufijo) {
		int disponible = LARGO_MAXIMO - sufijo.length() - 1;
		String recortada = base.length() > disponible ? base.substring(0, disponible) : base;
		return recortada + "-" + sufijo;
	}
}
