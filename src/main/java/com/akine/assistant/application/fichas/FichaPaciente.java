package com.akine.assistant.application.fichas;

import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Todo lo que se sabe de un paciente en una sola llamada: datos, turnos, casos, practicas y deudas. */
@Component
class FichaPaciente extends FichaCompuesta {

	static final int DIAS_DE_TURNOS = 30;
	static final int DIAS_DE_PRACTICAS = 60;

	FichaPaciente(ObjectProvider<CatalogoDeHerramientas> catalogo) {
		super(catalogo);
	}

	@Override
	public String nombre() {
		return "ficha_paciente";
	}

	@Override
	public String descripcion() {
		return "Resumen de una persona en una sola llamada: datos, turnos de los ultimos y proximos 30 dias, "
				+ "casos clinicos, practicas aplicadas en los ultimos 60 dias y deudas. Las partes que el usuario "
				+ "no puede ver se omiten y se avisan. Necesita persona_id (de buscar_persona). No da texto clinico.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"persona_id\":{\"type\":\"integer\",\"description\":\"Id de la persona, de buscar_persona\"}},"
				+ "\"required\":[\"persona_id\"]}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		if (personaId == null) {
			throw new IllegalArgumentException("Falta 'persona_id': buscá a la persona con buscar_persona");
		}
		Partes partes = partes(actor, ctx);
		ResultadoDeHerramienta datos = partes.llamar("datos_persona", persona(personaId));
		if (datos != null && datos.filas().isEmpty()) {
			// No existe o es de otra organizacion: no se consulta nada mas.
			return new ResultadoDeHerramienta(List.of(), datos.advertencias(), null, false);
		}
		Map<String, Object> turnos = persona(personaId);
		turnos.put("desde", ctx.hoy().minusDays(DIAS_DE_TURNOS).toString());
		turnos.put("hasta", ctx.hoy().plusDays(DIAS_DE_TURNOS).toString());
		partes.llamar("turnos", turnos);
		partes.llamar("casos_persona", persona(personaId));
		Map<String, Object> practicas = persona(personaId);
		practicas.put("desde", ctx.hoy().minusDays(DIAS_DE_PRACTICAS).toString());
		practicas.put("hasta", ctx.hoy().toString());
		partes.llamar("practicas_aplicadas", practicas);
		partes.llamar("deudas_persona", persona(personaId));
		return partes.resultado();
	}

	private static Map<String, Object> persona(Long personaId) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("persona_id", personaId);
		return m;
	}
}
