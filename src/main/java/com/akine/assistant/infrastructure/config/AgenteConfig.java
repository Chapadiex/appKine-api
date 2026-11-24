package com.akine.assistant.infrastructure.config;

import com.akine.assistant.application.AgenteDelAsistente;
import com.akine.assistant.application.AuditoriaDelAsistente;
import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.application.ProveedoresDelAgente;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Cableado del agente del asistente (docs/diseno/AKINE-asistente-agente.md §5.2).
 *
 * <p>{@code akine.asistente.agente.proveedor} elige el principal y {@code .alternativo} el que se usa
 * si el principal no responde, entre los beans de {@link ProveedorDeAgente} por su {@code nombre()}.
 * Los proveedores los registra {@code platform}; este modulo no conoce su implementacion. Un nombre
 * sin bean no impide arrancar (el asistente puede estar apagado, o sin key): la consulta responde 503.
 */
@Configuration
public class AgenteConfig {

	private static final Logger log = LoggerFactory.getLogger(AgenteConfig.class);

	static final String PROMPT = "asistente/prompt-agente.txt";

	@Bean
	public CatalogoDeHerramientas catalogoDeHerramientasDelAgente(ObjectProvider<HerramientaDelAgente> herramientas) {
		return new CatalogoDeHerramientas(herramientas.orderedStream().toList());
	}

	@Bean
	public ProveedoresDelAgente proveedoresDelAgente(ObjectProvider<ProveedorDeAgente> proveedores,
			@Value("${akine.asistente.agente.proveedor:claude}") String principal,
			@Value("${akine.asistente.agente.alternativo:}") String alternativo) {
		List<ProveedorDeAgente> disponibles = proveedores.orderedStream().toList();
		ProveedorDeAgente elegido = buscar(disponibles, principal);
		ProveedorDeAgente respaldo = alternativo == null || alternativo.isBlank() || alternativo.equals(principal)
				? null : buscar(disponibles, alternativo);
		avisarSiNoExiste("proveedor", principal, elegido, disponibles);
		if (respaldo == null && alternativo != null && !alternativo.equals(principal)) {
			avisarSiNoExiste("alternativo", alternativo, null, disponibles);
		}
		if (elegido == null) {
			log.info("No hay proveedor del agente '{}'; alternativo: {}", principal,
					respaldo == null ? "ninguno" : respaldo.nombre());
		}
		return new ProveedoresDelAgente(elegido, respaldo);
	}

	/**
	 * Un nombre mal escrito no impide arrancar, pero tampoco puede pasar callado: con proveedores
	 * registrados y ninguno con ese nombre, WARN con los que hay. Sin ninguno (asistente apagado) no
	 * hay nada que avisar.
	 */
	private static void avisarSiNoExiste(String propiedad, String nombre, ProveedorDeAgente encontrado,
			List<ProveedorDeAgente> disponibles) {
		if (encontrado != null || nombre == null || nombre.isBlank() || disponibles.isEmpty()) {
			return;
		}
		log.warn("akine.asistente.agente.{}='{}' no coincide con ningun proveedor; disponibles: {}", propiedad,
				nombre.strip(), disponibles.stream().map(ProveedorDeAgente::nombre).toList());
	}

	static ProveedorDeAgente buscar(List<ProveedorDeAgente> disponibles, String nombre) {
		if (nombre == null || nombre.isBlank()) {
			return null;
		}
		return disponibles.stream().filter(p -> nombre.strip().equals(p.nombre())).findFirst().orElse(null);
	}

	@Bean
	public AgenteDelAsistente agenteDelAsistente(ProveedoresDelAgente proveedores, CatalogoDeHerramientas catalogo,
			AuditoriaDelAsistente auditoria,
			@Value("${akine.asistente.agente.max-vueltas:8}") int maxVueltas,
			@Value("${akine.asistente.agente.plazo-total-segundos:30}") int plazoTotalSegundos) {
		return new AgenteDelAsistente(proveedores, catalogo, auditoria, AsistenteConfig.leerPrompt(PROMPT), maxVueltas,
				Duration.ofSeconds(plazoTotalSegundos), Clock.systemUTC());
	}
}
