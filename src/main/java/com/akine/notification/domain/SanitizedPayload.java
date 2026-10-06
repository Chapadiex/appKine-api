package com.akine.notification.domain;

import com.akine.notification.domain.exception.SensitiveContentException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Los datos de render de una notificacion, con la garantia de que ahi dentro no hay nada
 * sensible (RN-M26-002, T-11).
 *
 * <p><b>Diseñado para que sea dificil usarlo mal.</b> No alcanza con documentar "no pongas el
 * token aca": alguien lo va a poner. Por eso hay tres barreras, en orden:
 *
 * <ol>
 *   <li><b>Lista blanca de claves.</b> Solo se aceptan las de {@link #CLAVES_PERMITIDAS}. No
 *       existe una clave "enlace", "token" ni "url" que se pueda completar por error.</li>
 *   <li><b>Inspeccion del valor.</b> Se rechaza lo que parezca una URL, un JWT o una cadena
 *       larga de alta entropia con pinta de secreto, aunque venga bajo una clave permitida
 *       ({@code nombre = "https://.../activar?token=..."} no pasa).</li>
 *   <li><b>Longitud acotada.</b> Un nombre no mide 400 caracteres; lo que si mide asi suele
 *       ser un payload pegado donde no va.</li>
 * </ol>
 *
 * <p><b>Por que serializa a JSON a mano</b> en vez de usar un mapper: el mapa es plano y de
 * claves conocidas, y asi el modelo no arrastra ninguna dependencia de serializacion. Mas
 * importante: un mapper generico serializaria feliz cualquier objeto que le pasen, incluido
 * uno con un token adentro. Este no puede, porque solo sabe escribir {@code String -> String}
 * de claves conocidas.
 */
public final class SanitizedPayload {

	/** Unicas claves que el outbox acepta. Agregar una es una decision, no un descuido. */
	public static final Set<String> CLAVES_PERMITIDAS = Set.of(
			"nombre",
			"organizacionNombre",
			"invitadoPor",
			// AKINE-08.02: lo minimo para que un aviso de clase diga de que clase habla. Ningun
			// dato de otro participante, ningun dato clinico y ningun identificador interno.
			"claseTitulo",
			"claseInicio",
			"consultorioNombre",
			// AKINE E-5: lo minimo para que un aviso de turno diga de que turno habla (RF-M26-002:
			// "fecha, hora y consultorio"). El servicio es el nombre comercial de la oferta, no una
			// practica ni un diagnostico. Ni profesional, ni motivo, ni ningun identificador.
			"turnoInicio",
			"turnoInicioAnterior",
			"servicioNombre");

	/** Un nombre propio no llega ni cerca de este limite; un secreto pegado, si. */
	private static final int LARGO_MAXIMO_VALOR = 200;

	/** Fragmentos que delatan un enlace o un secreto, en minusculas. */
	private static final List<String> FRAGMENTOS_PROHIBIDOS = List.of(
			"http://", "https://", "://", "token=", "?token", "&token",
			"bearer ", "jwt", "password", "secret", "apikey", "api-key");

	private final Map<String, String> valores;

	private SanitizedPayload(Map<String, String> valores) {
		this.valores = Collections.unmodifiableMap(valores);
	}

	/** Payload sin datos de render. Es lo normal para los avisos que no personalizan nada. */
	public static SanitizedPayload vacio() {
		return new SanitizedPayload(new LinkedHashMap<>());
	}

	/**
	 * Valida y construye el payload.
	 *
	 * @throws SensitiveContentException si una clave no esta en la lista blanca o un valor
	 *                                   tiene pinta de enlace, token o secreto
	 */
	public static SanitizedPayload of(Map<String, String> datos) {
		if (datos == null || datos.isEmpty()) {
			return vacio();
		}
		Map<String, String> limpios = new LinkedHashMap<>();
		datos.forEach((clave, valor) -> {
			validarClave(clave);
			validarValor(clave, valor);
			limpios.put(clave, valor == null ? "" : valor);
		});
		return new SanitizedPayload(limpios);
	}

	private static void validarClave(String clave) {
		if (clave == null || !CLAVES_PERMITIDAS.contains(clave)) {
			throw new SensitiveContentException(String.valueOf(clave),
					"no esta en la lista blanca de claves de render " + ordenadas());
		}
	}

	private static void validarValor(String clave, String valor) {
		if (valor == null) {
			return;
		}
		if (valor.length() > LARGO_MAXIMO_VALOR) {
			throw new SensitiveContentException(clave,
					"supera los " + LARGO_MAXIMO_VALOR + " caracteres, no parece un dato de render");
		}
		String minusculas = valor.toLowerCase(Locale.ROOT);
		for (String fragmento : FRAGMENTOS_PROHIBIDOS) {
			if (minusculas.contains(fragmento)) {
				throw new SensitiveContentException(clave, "contiene un enlace o un secreto");
			}
		}
		if (pareceSecretoOpaco(valor)) {
			throw new SensitiveContentException(clave,
					"parece una cadena opaca de alta entropia (un token)");
		}
	}

	/**
	 * Heuristica del ultimo recurso: una cadena larga sin espacios, mezcla de mayusculas,
	 * minusculas y digitos, es un token o un hash. Un nombre propio no es asi.
	 */
	private static boolean pareceSecretoOpaco(String valor) {
		if (valor.length() < 24 || valor.indexOf(' ') >= 0) {
			return false;
		}
		boolean digito = false;
		boolean minuscula = false;
		boolean mayuscula = false;
		for (int i = 0; i < valor.length(); i++) {
			char c = valor.charAt(i);
			digito |= Character.isDigit(c);
			minuscula |= Character.isLowerCase(c);
			mayuscula |= Character.isUpperCase(c);
		}
		return digito && minuscula && mayuscula;
	}

	private static List<String> ordenadas() {
		String[] claves = CLAVES_PERMITIDAS.toArray(new String[0]);
		Arrays.sort(claves);
		return List.of(claves);
	}

	/** Valor de render, o {@code null} si no fue provisto. */
	public String get(String clave) {
		return valores.get(clave);
	}

	/** Valor de render, o {@code porDefecto} si no fue provisto o vino vacio. */
	public String getOrDefault(String clave, String porDefecto) {
		String valor = valores.get(clave);
		return valor == null || valor.isBlank() ? porDefecto : valor;
	}

	public Map<String, String> asMap() {
		return valores;
	}

	/** JSON plano, con las claves en el orden en que se declararon. */
	public String toJson() {
		StringBuilder json = new StringBuilder("{");
		boolean primero = true;
		for (Map.Entry<String, String> entrada : valores.entrySet()) {
			if (!primero) {
				json.append(',');
			}
			primero = false;
			json.append('"').append(entrada.getKey()).append("\":\"")
					.append(escapar(entrada.getValue())).append('"');
		}
		return json.append('}').toString();
	}

	/**
	 * Relee un payload guardado. Vuelve a pasar por las mismas validaciones: si una fila vieja
	 * trae algo que hoy consideramos sensible, se rechaza al leerla y no se envia.
	 */
	public static SanitizedPayload fromJson(String json) {
		if (json == null || json.isBlank()) {
			return vacio();
		}
		String cuerpo = json.trim();
		if (!cuerpo.startsWith("{") || !cuerpo.endsWith("}")) {
			throw new IllegalArgumentException("El payload del outbox no es un objeto JSON plano");
		}
		cuerpo = cuerpo.substring(1, cuerpo.length() - 1).trim();
		if (cuerpo.isEmpty()) {
			return vacio();
		}
		Map<String, String> datos = new LinkedHashMap<>();
		for (String parCrudo : dividirEnPares(cuerpo)) {
			// El par se recorta UNA vez y todos los indices se calculan sobre el recortado.
			// Calcular el separador sobre el crudo y aplicarlo sobre el recortado corre la
			// ventana tantos caracteres como espacios haya adelante, y la clave se lleva
			// puesta la comilla de cierre: `organizacionNombre"` en vez de
			// `organizacionNombre`. Como el primer par nunca tiene espacio adelante, un
			// payload de UNA sola clave se leia bien y el defecto solo aparecia a partir de
			// la segunda —que es exactamente por que los correos de activacion salian y los
			// de invitacion no—.
			String par = parCrudo.trim();
			int separador = par.indexOf("\":");
			if (!par.startsWith("\"") || separador < 0) {
				throw new IllegalArgumentException("El payload del outbox no es un objeto JSON plano");
			}
			String clave = par.substring(1, separador);
			String valor = par.substring(separador + 2).trim();
			if (valor.length() < 2 || valor.charAt(0) != '"' || valor.charAt(valor.length() - 1) != '"') {
				throw new IllegalArgumentException("El payload del outbox solo admite valores de texto");
			}
			datos.put(clave, desescapar(valor.substring(1, valor.length() - 1)));
		}
		return of(datos);
	}

	/** Parte {@code "a":"x,y","b":"z"} por las comas que estan FUERA de las comillas. */
	private static List<String> dividirEnPares(String cuerpo) {
		List<String> pares = new java.util.ArrayList<>();
		StringBuilder actual = new StringBuilder();
		boolean dentroDeTexto = false;
		boolean escapado = false;
		for (int i = 0; i < cuerpo.length(); i++) {
			char c = cuerpo.charAt(i);
			if (escapado) {
				escapado = false;
			} else if (c == '\\') {
				escapado = true;
			} else if (c == '"') {
				dentroDeTexto = !dentroDeTexto;
			} else if (c == ',' && !dentroDeTexto) {
				pares.add(actual.toString());
				actual.setLength(0);
				continue;
			}
			actual.append(c);
		}
		pares.add(actual.toString());
		return pares;
	}

	private static String escapar(String valor) {
		StringBuilder salida = new StringBuilder(valor.length());
		for (int i = 0; i < valor.length(); i++) {
			char c = valor.charAt(i);
			switch (c) {
				case '"' -> salida.append("\\\"");
				case '\\' -> salida.append("\\\\");
				case '\n' -> salida.append("\\n");
				case '\r' -> salida.append("\\r");
				case '\t' -> salida.append("\\t");
				default -> salida.append(c);
			}
		}
		return salida.toString();
	}

	private static String desescapar(String valor) {
		StringBuilder salida = new StringBuilder(valor.length());
		for (int i = 0; i < valor.length(); i++) {
			char c = valor.charAt(i);
			if (c == '\\' && i + 1 < valor.length()) {
				char siguiente = valor.charAt(++i);
				switch (siguiente) {
					case 'n' -> salida.append('\n');
					case 'r' -> salida.append('\r');
					case 't' -> salida.append('\t');
					default -> salida.append(siguiente);
				}
			} else {
				salida.append(c);
			}
		}
		return salida.toString();
	}

	@Override
	public String toString() {
		return "SanitizedPayload" + valores.keySet();
	}
}
