package com.akine.knowledge.application.manual;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.ManualInvalidoException;
import com.akine.knowledge.domain.manual.PasajeDelManual;
import com.akine.knowledge.domain.manual.TipoDePasaje;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.Servicio;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Lee las fichas del manual del classpath y las parte en pasajes. Valida el formato y, si algo esta
 * mal, lanza {@link ManualInvalidoException}: un manual roto hace fallar el arranque, no una consulta.
 */
public final class CargadorDelManual {

	/** Donde viven las fichas reales. */
	public static final String UBICACION = "classpath:manual/*.md";

	private static final Pattern PERMISO = Pattern.compile("[a-z][a-z-]*:[a-z][a-z-]*");
	private static final Pattern SIN_LETRAS = Pattern.compile("[^a-z0-9]+");
	private static final Map<String, TipoDePasaje> SECCIONES_CON_SUBTITULOS = Map.of(
			"tareas", TipoDePasaje.TAREA,
			"terminos", TipoDePasaje.TERMINO);
	private static final Map<String, TipoDePasaje> SECCIONES_FIJAS = Map.of(
			"para que sirve", TipoDePasaje.PARA_QUE_SIRVE,
			"que significa cada estado", TipoDePasaje.ESTADOS,
			"problemas comunes", TipoDePasaje.PROBLEMAS);

	private CargadorDelManual() {
	}

	public static List<FichaDelManual> cargar(ResourcePatternResolver resolver) {
		return cargar(resolver, UBICACION);
	}

	public static List<FichaDelManual> cargar(ResourcePatternResolver resolver, String ubicacion) {
		Resource[] archivos;
		try {
			archivos = resolver.getResources(ubicacion);
		}
		catch (IOException e) {
			throw new ManualInvalidoException("No se pudo listar el manual en " + ubicacion, e);
		}
		List<FichaDelManual> fichas = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (Resource archivo : archivos) {
			String nombre = archivo.getFilename();
			FichaDelManual ficha = leer(nombre, contenido(archivo));
			if (!ids.add(ficha.id())) {
				throw new ManualInvalidoException("Ficha repetida: " + ficha.id());
			}
			fichas.add(ficha);
		}
		for (FichaDelManual ficha : fichas) {
			for (String relacionada : ficha.relacionadas()) {
				if (!ids.contains(relacionada)) {
					throw new ManualInvalidoException(
							ficha.id() + ".md: la relacionada '" + relacionada + "' no existe");
				}
			}
		}
		fichas.sort(Comparator.comparing(FichaDelManual::grupo).thenComparing(FichaDelManual::titulo));
		return List.copyOf(fichas);
	}

	/** Minusculas, sin tildes, con guiones: el mismo slug para ids de pasaje y busqueda. */
	public static String slug(String texto) {
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String limpio = SIN_LETRAS.matcher(sinTildes.toLowerCase(Locale.ROOT)).replaceAll("-");
		return limpio.replaceAll("^-+|-+$", "");
	}

	static FichaDelManual leer(String nombreArchivo, String texto) {
		String contenido = texto.replace("\r\n", "\n");
		if (!contenido.startsWith("---\n")) {
			throw new ManualInvalidoException(nombreArchivo + ": falta el frontmatter entre ---");
		}
		int fin = contenido.indexOf("\n---\n", 4);
		if (fin < 0) {
			throw new ManualInvalidoException(nombreArchivo + ": el frontmatter no se cierra con ---");
		}
		Map<String, Object> meta = yaml(nombreArchivo, contenido.substring(4, fin));
		String cuerpo = contenido.substring(fin + 5).strip();

		String id = texto(meta, "id", nombreArchivo, true);
		String esperado = nombreArchivo == null ? id : nombreArchivo.replaceFirst("\\.md$", "");
		if (!id.equals(esperado)) {
			throw new ManualInvalidoException(nombreArchivo + ": el id '" + id + "' no coincide con el archivo");
		}
		String titulo = texto(meta, "titulo", nombreArchivo, true);
		String grupo = texto(meta, "grupo", nombreArchivo, true);
		// Sin rutas es valido: primeros pasos y glosario no son una pantalla, se llega por el indice.
		List<String> rutas = lista(meta, "rutas", nombreArchivo);
		Set<String> permisos = new LinkedHashSet<>(lista(meta, "permisos", nombreArchivo));
		for (String permiso : permisos) {
			if (!PERMISO.matcher(permiso).matches()) {
				throw new ManualInvalidoException(nombreArchivo + ": permiso con formato invalido '" + permiso + "'");
			}
		}
		FuncionIa funcionIa = enumeracion(FuncionIa.class, texto(meta, "funcionIa", nombreArchivo, false),
				nombreArchivo, "funcionIa");
		Servicio servicio = enumeracion(Servicio.class, texto(meta, "servicio", nombreArchivo, false),
				nombreArchivo, "servicio");

		List<PasajeDelManual> pasajes = pasajes(id, cuerpo);
		if (pasajes.stream().noneMatch(p -> p.tipo() == TipoDePasaje.PARA_QUE_SIRVE)) {
			throw new ManualInvalidoException(nombreArchivo + ": falta la seccion '## Para qué sirve'");
		}
		return new FichaDelManual(id, titulo, grupo, rutas, permisos, funcionIa, servicio,
				lista(meta, "relacionadas", nombreArchivo), lista(meta, "palabras", nombreArchivo), cuerpo, pasajes);
	}

	/** Un pasaje por {@code ###} dentro de "Tareas" y uno por cada seccion fija. */
	static List<PasajeDelManual> pasajes(String fichaId, String cuerpo) {
		List<PasajeDelManual> pasajes = new ArrayList<>();
		Map<String, Integer> usados = new LinkedHashMap<>();
		String seccion = null;
		String titulo = null;
		TipoDePasaje tipo = null;
		StringBuilder buffer = new StringBuilder();
		for (String linea : cuerpo.split("\n", -1)) {
			if (linea.startsWith("## ")) {
				cerrar(pasajes, usados, fichaId, tipo, titulo, buffer);
				seccion = normalizar(linea.substring(3));
				tipo = SECCIONES_FIJAS.get(seccion);
				titulo = tipo == null ? null : linea.substring(3).strip();
				buffer.setLength(0);
			}
			else if (linea.startsWith("### ") && SECCIONES_CON_SUBTITULOS.containsKey(seccion)) {
				cerrar(pasajes, usados, fichaId, tipo, titulo, buffer);
				tipo = SECCIONES_CON_SUBTITULOS.get(seccion);
				titulo = linea.substring(4).strip();
				buffer.setLength(0);
			}
			else if (tipo != null) {
				buffer.append(linea).append('\n');
			}
		}
		cerrar(pasajes, usados, fichaId, tipo, titulo, buffer);
		return pasajes;
	}

	private static void cerrar(List<PasajeDelManual> pasajes, Map<String, Integer> usados, String fichaId,
			TipoDePasaje tipo, String titulo, StringBuilder buffer) {
		if (tipo == null || titulo == null) {
			return;
		}
		String base = slug(titulo);
		int veces = usados.merge(base, 1, Integer::sum);
		String id = fichaId + "#" + (veces == 1 ? base : base + "-" + veces);
		pasajes.add(new PasajeDelManual(id, fichaId, tipo, titulo, buffer.toString().strip()));
	}

	private static String normalizar(String titulo) {
		return slug(titulo).replace('-', ' ');
	}

	private static String contenido(Resource archivo) {
		try (InputStream in = archivo.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new ManualInvalidoException("No se pudo leer " + archivo.getFilename(), e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> yaml(String archivo, String texto) {
		try {
			Object leido = new Yaml(new SafeConstructor(new LoaderOptions())).load(texto);
			if (!(leido instanceof Map<?, ?> mapa)) {
				throw new ManualInvalidoException(archivo + ": el frontmatter no es un mapa");
			}
			return (Map<String, Object>) mapa;
		}
		catch (RuntimeException e) {
			if (e instanceof ManualInvalidoException m) {
				throw m;
			}
			throw new ManualInvalidoException(archivo + ": frontmatter invalido", e);
		}
	}

	private static String texto(Map<String, Object> meta, String clave, String archivo, boolean obligatorio) {
		Object valor = meta.get(clave);
		if (valor == null || valor.toString().isBlank()) {
			if (obligatorio) {
				throw new ManualInvalidoException(archivo + ": falta " + clave);
			}
			return null;
		}
		return valor.toString().strip();
	}

	private static List<String> lista(Map<String, Object> meta, String clave, String archivo) {
		Object valor = meta.get(clave);
		if (valor == null) {
			return List.of();
		}
		if (!(valor instanceof Collection<?> coleccion)) {
			throw new ManualInvalidoException(archivo + ": " + clave + " tiene que ser una lista");
		}
		return coleccion.stream().map(Object::toString).map(String::strip).filter(s -> !s.isEmpty()).toList();
	}

	private static <E extends Enum<E>> E enumeracion(Class<E> tipo, String valor, String archivo, String clave) {
		if (valor == null) {
			return null;
		}
		try {
			return Enum.valueOf(tipo, valor);
		}
		catch (IllegalArgumentException e) {
			throw new ManualInvalidoException(archivo + ": " + clave + " desconocido '" + valor + "', vale uno de "
					+ Arrays.toString(tipo.getEnumConstants()));
		}
	}
}
