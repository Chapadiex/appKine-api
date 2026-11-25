package com.akine.clinical.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.CasoClinicoView;
import com.akine.clinical.application.OperatingActor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code casos_persona}: los casos clinicos activos de una persona. Es lectura clinica: la hace
 * {@link CasoClinicoService#listarDePersona}, que exige {@code hc:read}, aplica la regla de justificacion y la
 * audita (DP-03). No devuelve diagnostico ni objetivos: solo numero, oferta, estado y fecha de apertura.
 */
@Component
class CasosEnElAsistente implements HerramientaDelAgente {

	private static final String JUSTIFICACION = "Consulta al asistente";
	private static final int TOPE = 50;

	private final CasoClinicoService casos;

	CasosEnElAsistente(CasoClinicoService casos) {
		this.casos = casos;
	}

	@Override
	public String nombre() {
		return "casos_persona";
	}

	@Override
	public String descripcion() {
		return "Lista los casos clinicos activos de una persona (numero de caso, oferta, estado y fecha de "
				+ "apertura). No devuelve diagnosticos ni notas clinicas. Necesita persona_id.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"persona_id\":{\"type\":\"integer\",\"description\":\"Id de la persona (paciente)\"}},"
				+ "\"required\":[\"persona_id\"]}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(
			ActorDelAsistente actor, Map<String, Object> entrada, ContextoDeConsulta ctx) {
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		if (personaId == null) {
			throw new IllegalArgumentException("Falta 'persona_id'");
		}
		if (actor.organizationId() == null) {
			return ResultadoDeHerramienta.denegado();
		}
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		// Autoriza ANTES de buscar la historia: sin hc:read el rechazo es el mismo tenga o no
		// historia la persona, y no se filtra quien tiene registro clinico.
		Optional<List<CasoClinicoView>> encontrados =
				casos.listarDePersona(operador, personaId, true, JUSTIFICACION);
		if (encontrados.isEmpty()) {
			return new ResultadoDeHerramienta(List.of(), List.of("No encontré esa persona"), null, false);
		}
		List<CasoClinicoView> vistas = encontrados.get();
		List<String> avisos = vistas.size() > TOPE
				? List.of("Hay " + vistas.size() + " resultados; muestro los primeros " + TOPE)
				: List.of();
		List<Map<String, Object>> filas = vistas.stream().limit(TOPE).map(CasosEnElAsistente::fila).toList();
		return new ResultadoDeHerramienta(filas, avisos, null, false);
	}

	private static Map<String, Object> fila(CasoClinicoView caso) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("caso_id", caso.id());
		fila.put("numero_caso", caso.numeroCaso());
		fila.put("oferta_id", caso.ofertaId());
		fila.put("oferta", caso.ofertaNombre());
		fila.put("estado", caso.estado());
		fila.put("abierto_en", caso.abiertoEn() == null ? null : caso.abiertoEn().toString());
		return fila;
	}
}
