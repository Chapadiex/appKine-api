package com.akine.assistant.application.fichas;

import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base de las fichas: una herramienta del agente que arma su respuesta llamando a otras del catalogo, de a
 * una y en el hilo de la consulta (el contexto de organizacion y sede es un ThreadLocal: en otro hilo no
 * estaria). Una ficha nunca llama a otra ficha. El catalogo se pide por {@link ObjectProvider} porque
 * recoge todas las herramientas, fichas incluidas: inyectarlo directo cerraria un ciclo de beans.
 *
 * <p>Cada parte es una seccion {@code {seccion, filas, advertencias}}; una parte rechazada por permiso se
 * omite y queda como {@code omitida_por_permiso: <herramienta>}.
 */
abstract class FichaCompuesta implements HerramientaDelAgente {

	static final String OMITIDA = "omitida_por_permiso: ";

	private final ObjectProvider<CatalogoDeHerramientas> catalogo;

	FichaCompuesta(ObjectProvider<CatalogoDeHerramientas> catalogo) {
		this.catalogo = catalogo;
	}

	/** Las partes de una ficha en una consulta. */
	final class Partes {

		private final ActorDelAsistente actor;
		private final ContextoDeConsulta ctx;
		private final List<Map<String, Object>> secciones = new ArrayList<>();
		private final Set<String> advertencias = new LinkedHashSet<>();
		private int intentadas;
		private int denegadas;

		Partes(ActorDelAsistente actor, ContextoDeConsulta ctx) {
			this.actor = actor;
			this.ctx = ctx;
		}

		/** Ejecuta la herramienta; devuelve su resultado, o {@code null} si fue denegada (queda la advertencia). */
		ResultadoDeHerramienta llamar(String herramienta, Map<String, Object> entrada) {
			intentadas++;
			ResultadoDeHerramienta r = catalogo.getObject().ejecutar(herramienta, actor, entrada, ctx);
			if (r.sinPermiso()) {
				denegadas++;
				advertencias.add(OMITIDA + herramienta);
				return null;
			}
			agregar(herramienta, r.filas(), r.advertencias());
			return r;
		}

		void agregar(String seccion, List<Map<String, Object>> filas, List<String> avisos) {
			Map<String, Object> fila = new LinkedHashMap<>();
			fila.put("seccion", seccion);
			fila.put("filas", filas);
			fila.put("advertencias", avisos);
			secciones.add(fila);
			advertencias.addAll(avisos);
		}

		/** Una advertencia de la ficha misma, no de una de sus partes. */
		void advertir(String aviso) {
			advertencias.add(aviso);
		}

		ResultadoDeHerramienta resultado() {
			if (intentadas > 0 && denegadas == intentadas) {
				return ResultadoDeHerramienta.denegado();
			}
			return new ResultadoDeHerramienta(secciones, List.copyOf(advertencias), null, false);
		}
	}

	Partes partes(ActorDelAsistente actor, ContextoDeConsulta ctx) {
		return new Partes(actor, ctx);
	}
}
