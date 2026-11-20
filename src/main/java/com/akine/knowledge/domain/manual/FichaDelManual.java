package com.akine.knowledge.domain.manual;

import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.Servicio;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * La ficha del manual de una pantalla (docs/diseno/AKINE-manual-de-usuario.md §5.1). Es la misma para
 * todos los centros: se despliega con la aplicacion y no es dato de ningun tenant.
 *
 * @param rutas     patrones de ruta del frontend; un segmento {@code :x} acepta cualquier valor
 * @param permisos  alcanza con tener uno; vacio = cualquiera con sesion
 * @param funcionIa la funcion de IA del centro que la pantalla necesita, o {@code null}
 * @param servicio  el servicio contratado que la pantalla necesita, o {@code null}
 * @param palabras  sinonimos que cuentan para la busqueda
 * @param markdown  el cuerpo completo, sin el frontmatter
 */
public record FichaDelManual(
		String id,
		String titulo,
		String grupo,
		List<String> rutas,
		Set<String> permisos,
		FuncionIa funcionIa,
		Servicio servicio,
		List<String> relacionadas,
		List<String> palabras,
		String markdown,
		List<PasajeDelManual> pasajes) {

	public FichaDelManual {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(titulo, "titulo");
		Objects.requireNonNull(grupo, "grupo");
		rutas = rutas == null ? List.of() : List.copyOf(rutas);
		permisos = permisos == null ? Set.of() : Set.copyOf(permisos);
		relacionadas = relacionadas == null ? List.of() : List.copyOf(relacionadas);
		palabras = palabras == null ? List.of() : List.copyOf(palabras);
		markdown = markdown == null ? "" : markdown;
		pasajes = pasajes == null ? List.of() : List.copyOf(pasajes);
	}
}
