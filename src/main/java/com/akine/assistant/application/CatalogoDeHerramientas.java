package com.akine.assistant.application;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.platform.spi.modelo.agente.DefinicionDeHerramienta;
import org.springframework.security.access.AccessDeniedException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Las herramientas que el agente puede ofrecerle al modelo, una por capacidad, cada una implementada
 * por el modulo dueno del dato (docs/diseno/AKINE-asistente-agente.md §5.3).
 *
 * <p>El orden de {@link #disponiblesPara} es estable (por nombre): el catalogo va en el prefijo
 * cacheado del prompt y un orden que cambia rompe la cache.
 */
public class CatalogoDeHerramientas {

	private final Map<String, HerramientaDelAgente> porNombre = new TreeMap<>();

	public CatalogoDeHerramientas(List<HerramientaDelAgente> herramientas) {
		for (HerramientaDelAgente herramienta : herramientas) {
			if (porNombre.put(herramienta.nombre(), herramienta) != null) {
				throw new IllegalStateException("Dos herramientas del agente con el nombre " + herramienta.nombre());
			}
		}
	}

	/** Las que el actor puede usar; las demas no se le ofrecen al modelo. */
	public List<DefinicionDeHerramienta> disponiblesPara(ActorDelAsistente actor) {
		return porNombre.values().stream()
				.filter(h -> h.disponiblePara(actor))
				.sorted(Comparator.comparing(HerramientaDelAgente::nombre))
				.map(h -> new DefinicionDeHerramienta(h.nombre(), h.descripcion(), h.esquemaEntrada()))
				.toList();
	}

	/**
	 * Ejecuta la herramienta con el actor. Un rechazo del servicio dueno del dato, o una herramienta que
	 * el actor no tiene, vuelven como {@link ResultadoDeHerramienta#denegado()}: el modelo lo dice y la
	 * respuesta es 200. Sin transaccion compartida: cada lectura vive en la de su servicio.
	 *
	 * @throws IllegalArgumentException si no existe una herramienta con ese nombre
	 */
	public ResultadoDeHerramienta ejecutar(String nombre, ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		HerramientaDelAgente herramienta = porNombre.get(nombre);
		if (herramienta == null) {
			throw new IllegalArgumentException("No hay una herramienta llamada " + nombre);
		}
		if (!herramienta.disponiblePara(actor)) {
			return ResultadoDeHerramienta.denegado();
		}
		try {
			return herramienta.ejecutar(actor, entrada == null ? Map.of() : entrada, ctx);
		}
		catch (AccessDeniedException e) {
			return ResultadoDeHerramienta.denegado();
		}
	}

	public boolean existe(String nombre) {
		return porNombre.containsKey(nombre);
	}
}
