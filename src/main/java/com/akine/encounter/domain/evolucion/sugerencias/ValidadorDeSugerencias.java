package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.platform.spi.texto.DetectorDeInstrucciones;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valida lo que devolvio el modelo contra lo que se le envio (diseno fase 2, 6). Nunca lanza por una salida
 * mala: cualquier falla de forma es un descarte contado.
 */
public final class ValidadorDeSugerencias {

	static final int MAXIMO_ACEPTADAS = 5;

	static final int MAXIMO_TEXTO = 280;

	static final int MAXIMO_FUNDAMENTO = 600;

	private static final Pattern MARCAS_COMBINANTES = Pattern.compile("\\p{M}+");

	private final JsonMapper json = JsonMapper.builder().build();

	private final List<Pattern> formasPrescriptivas;

	private final DetectorDeInstrucciones detector;

	public ValidadorDeSugerencias(List<String> formasPrescriptivas, DetectorDeInstrucciones detector) {
		this.formasPrescriptivas = formasPrescriptivas.stream().map(ValidadorDeSugerencias::normalizar)
				.filter(f -> !f.isEmpty())
				.map(f -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(f) + "(?![a-z0-9])")).toList();
		this.detector = detector;
	}

	public ResultadoDeValidacion validar(String salidaCruda, List<HallazgoConEvidencia> enviados,
			Set<String> numerosPermitidos) {
		Map<ReglaDeDescarte, Integer> descartes = new EnumMap<>(ReglaDeDescarte.class);
		JsonNode lista = leerLista(salidaCruda);
		if (lista == null) {
			descartes.put(ReglaDeDescarte.ESQUEMA, 1);
			return new ResultadoDeValidacion(List.of(), descartes);
		}
		Map<CodigoDeHallazgo, HallazgoConEvidencia> porCodigo = new LinkedHashMap<>();
		Map<String, PasajeEnviado> porId = new LinkedHashMap<>();
		for (HallazgoConEvidencia e : enviados) {
			porCodigo.putIfAbsent(e.hallazgo().codigo(), e);
			e.pasajes().forEach(p -> porId.putIfAbsent(p.id(), p));
		}
		Set<String> permitidos = new HashSet<>();
		numerosPermitidos.forEach(n -> permitidos.add(normalizarNumeroOEntero(n)));

		List<SugerenciaPropuesta> aceptadas = new ArrayList<>();
		for (JsonNode item : lista) {
			SugerenciaPropuesta s;
			ReglaDeDescarte falla;
			try {
				Resultado r = evaluar(item, porCodigo, porId, permitidos);
				s = r.sugerencia;
				falla = r.falla;
			}
			catch (RuntimeException e) {
				s = null;
				falla = ReglaDeDescarte.ESQUEMA;
			}
			if (s != null) {
				aceptadas.add(s);
			}
			else {
				descartes.merge(falla, 1, Integer::sum);
			}
		}
		if (aceptadas.size() > MAXIMO_ACEPTADAS) {
			aceptadas = new ArrayList<>(aceptadas.subList(0, MAXIMO_ACEPTADAS));
		}
		return new ResultadoDeValidacion(aceptadas, descartes);
	}

	private record Resultado(SugerenciaPropuesta sugerencia, ReglaDeDescarte falla) {
	}

	private Resultado evaluar(JsonNode item, Map<CodigoDeHallazgo, HallazgoConEvidencia> porCodigo,
			Map<String, PasajeEnviado> porId, Set<String> permitidos) {
		// 1. esquema
		if (!item.isObject() || !item.path("hallazgo").isString() || !item.path("texto").isString()
				|| !item.path("fundamento").isString() || !item.path("citas").isArray()) {
			return descarte(ReglaDeDescarte.ESQUEMA);
		}
		List<String> ids = new ArrayList<>();
		for (JsonNode c : item.get("citas")) {
			if (!c.isString()) {
				return descarte(ReglaDeDescarte.ESQUEMA);
			}
			ids.add(c.asString().strip());
		}
		String texto = item.get("texto").asString();
		String fundamento = item.get("fundamento").asString();

		// 2. hallazgo enviado
		CodigoDeHallazgo codigo = codigoDe(item.get("hallazgo").asString());
		if (codigo == null || !porCodigo.containsKey(codigo)) {
			return descarte(ReglaDeDescarte.HALLAZGO_NO_ENVIADO);
		}
		// 3. citas
		if (ids.isEmpty()) {
			return descarte(ReglaDeDescarte.SIN_CITAS);
		}
		List<PasajeEnviado> citas = new ArrayList<>();
		for (String id : ids) {
			PasajeEnviado p = porId.get(id);
			if (p == null) {
				return descarte(ReglaDeDescarte.CITA_DESCONOCIDA);
			}
			if (!citas.contains(p)) {
				citas.add(p);
			}
		}
		// 4. citas del hallazgo
		for (PasajeEnviado p : citas) {
			if (!p.hallazgos().contains(codigo)) {
				return descarte(ReglaDeDescarte.CITA_DE_OTRO_HALLAZGO);
			}
		}
		// 5. numeros
		Set<String> validos = new HashSet<>(permitidos);
		citas.forEach(p -> validos.addAll(NumerosEnTexto.extraer(p.pasaje().texto())));
		Set<String> usados = NumerosEnTexto.extraer(texto);
		usados.addAll(NumerosEnTexto.extraer(fundamento));
		if (!validos.containsAll(usados)) {
			return descarte(ReglaDeDescarte.NUMERO_NO_ENVIADO);
		}
		// 6. formas prescriptivas (en el texto)
		String normalizado = normalizar(texto);
		for (Pattern forma : formasPrescriptivas) {
			if (forma.matcher(normalizado).find()) {
				return descarte(ReglaDeDescarte.FORMA_PRESCRIPTIVA);
			}
		}
		// 7. largo
		if (texto.isBlank() || fundamento.isBlank() || texto.length() > MAXIMO_TEXTO
				|| fundamento.length() > MAXIMO_FUNDAMENTO) {
			return descarte(ReglaDeDescarte.LARGO);
		}
		// 8. instrucciones
		if (detector.esSospechoso(texto) || detector.esSospechoso(fundamento)) {
			return descarte(ReglaDeDescarte.INSTRUCCION);
		}
		return new Resultado(new SugerenciaPropuesta(codigo, texto.strip(), fundamento.strip(), citas), null);
	}

	private static Resultado descarte(ReglaDeDescarte regla) {
		return new Resultado(null, regla);
	}

	/** La lista {@code sugerencias} de la salida, o {@code null} si no se puede leer. */
	private JsonNode leerLista(String salida) {
		if (salida == null || salida.isBlank()) {
			return null;
		}
		try {
			JsonNode raiz = json.readTree(salida);
			if (raiz != null && raiz.isObject() && raiz.path("sugerencias").isArray()) {
				return raiz.get("sugerencias");
			}
			return null;
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static CodigoDeHallazgo codigoDe(String valor) {
		try {
			return CodigoDeHallazgo.valueOf(valor.strip());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String normalizarNumeroOEntero(String n) {
		try {
			return NumerosEnTexto.normalizar(n.strip());
		}
		catch (NumberFormatException e) {
			return n;
		}
	}

	private static String normalizar(String texto) {
		String sinTildes = MARCAS_COMBINANTES.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
		return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}
}
