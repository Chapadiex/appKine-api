package com.akine.person.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalizacion de los textos por los que se busca y se compara una {@link Persona} (M07).
 *
 * <h2>Por que las claves se guardan materializadas y no se calculan en el WHERE</h2>
 *
 * <p>Un {@code WHERE UPPER(apellido) LIKE ...} no usa el indice: MySQL tiene que evaluar la
 * funcion fila por fila. El padron de personas de un centro es la primera tabla de este sistema
 * que va a tener decenas de miles de filas —RNF-M07-004 pide tiempos compatibles con el volumen
 * operativo— y es la unica que se consulta en el mostrador con el paciente esperando. Por eso
 * {@code documento_clave}, {@code apellido_clave}, {@code nombre_clave} y {@code telefono_clave}
 * son columnas reales, escritas por {@link Persona} en el constructor y en cada edicion.
 *
 * <h2>Que hace exactamente, y por que cada paso</h2>
 *
 * <ul>
 *   <li><b>Acentos fuera.</b> "Pérez" y "Perez" son la misma persona buscada por dos operadores
 *       distintos, y la segunda es la que se tipea con apuro. Se descompone en NFD y se quitan
 *       las marcas diacriticas, que es la unica forma que no depende de una tabla de reemplazos
 *       escrita a mano.</li>
 *   <li><b>Mayusculas.</b> Con {@link Locale#ROOT} y no con el locale del servidor: en turco,
 *       {@code "i".toUpperCase()} devuelve una i con punto y la clave de una misma persona
 *       dependeria de la configuracion regional de la maquina que corre el backend.</li>
 *   <li><b>Separadores fuera, solo en el documento y el telefono.</b> "12.345.678" y "12345678"
 *       son el mismo documento; "+54 11 5555-0000" y "1155550000" son el mismo telefono. En el
 *       nombre NO se quitan los espacios: "Ana Maria" y "Anamaria" no son la misma persona, y
 *       colapsarlos produciria falsos duplicados que despues nadie entiende.</li>
 * </ul>
 *
 * <p><b>Lo que deliberadamente NO hace: fonetica.</b> Nada de Soundex ni de distancia de
 * Levenshtein. Un matching difuso convierte la deteccion de duplicados en una lista de
 * sugerencias que el operador aprende a ignorar, y ademas no se puede indexar. La deteccion de
 * esta etapa es exacta sobre claves normalizadas: pocos falsos positivos, cero magia. Si un dia
 * hace falta fonetica, va como una columna mas y con su propia decision documentada, no
 * escondida dentro de este metodo.
 */
public final class ClaveDeBusqueda {

	/** Todo lo que no sea letra o digito, para las claves donde el separador no significa nada. */
	private static final String SEPARADORES = "[^\\p{Alnum}]";

	/** Marcas diacriticas que deja sueltas la descomposicion NFD. */
	private static final String DIACRITICOS = "\\p{M}";

	private ClaveDeBusqueda() {
		// Utilidad de normalizacion.
	}

	/**
	 * Clave de un documento: sin acentos, sin separadores, en mayusculas.
	 *
	 * <p>Devuelve {@code null} para una entrada nula o que se queda vacia despues de limpiar
	 * —"---" no es un documento—, y ese {@code null} es el que hace que la persona quede fuera
	 * del unique, que es el comportamiento correcto: sin documento no hay identidad que
	 * comparar.
	 */
	public static String deDocumento(String valor) {
		return vacioEsNulo(sinAcentos(valor).replaceAll(SEPARADORES, ""));
	}

	/**
	 * Clave de un nombre o apellido: sin acentos, en mayusculas, con los espacios internos
	 * colapsados a uno solo. Los espacios se conservan a proposito, ver la cabecera.
	 */
	public static String deNombre(String valor) {
		return vacioEsNulo(sinAcentos(valor).replaceAll("\\s+", " ").strip());
	}

	/**
	 * Clave de un telefono: solo sus digitos.
	 *
	 * <p>Se conserva el prefijo internacional si el operador lo tipeo, en vez de intentar
	 * deducir un formato canonico argentino. Adivinar cual de "11 5555-0000", "011 5555-0000" y
	 * "+54 9 11 5555-0000" es "el mismo" telefono exige reglas de pais que cambian y que este
	 * sistema no tiene por que conocer; una busqueda por los ultimos digitos resuelve el caso
	 * real sin inventar ninguna.
	 */
	public static String deTelefono(String valor) {
		return vacioEsNulo(valor == null ? null : valor.replaceAll("\\D", ""));
	}

	private static String sinAcentos(String valor) {
		if (valor == null) {
			return "";
		}
		return Normalizer.normalize(valor, Normalizer.Form.NFD)
				.replaceAll(DIACRITICOS, "")
				.toUpperCase(Locale.ROOT)
				.strip();
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor;
	}
}
