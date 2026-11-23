package com.akine.assistant.application.fichas;

import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Una actividad de la sede con su arancel vigente, opcionalmente calculado para una persona. */
@Component
class FichaPractica extends FichaCompuesta {

	FichaPractica(ObjectProvider<CatalogoDeHerramientas> catalogo) {
		super(catalogo);
	}

	@Override
	public String nombre() {
		return "ficha_practica";
	}

	@Override
	public String descripcion() {
		return "Una actividad de la sede y cuanto cuesta hoy: la oferta y su arancel vigente; con persona_id, "
				+ "el calculo con la cobertura de esa persona. Necesita el nombre de la actividad.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"oferta\":{\"type\":\"string\",\"description\":\"Nombre de la actividad, por ejemplo Kinesiologia\"},"
				+ "\"persona_id\":{\"type\":\"integer\",\"description\":\"Id de la persona, para calcular con su cobertura\"}},"
				+ "\"required\":[\"oferta\"]}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		String oferta = EntradaDeHerramienta.texto(entrada, "oferta");
		if (oferta == null) {
			throw new IllegalArgumentException("Falta 'oferta': el nombre de la actividad");
		}
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		Partes partes = partes(actor, ctx);
		partes.llamar("buscar_oferta", Map.of("texto", oferta));
		Map<String, Object> arancel = new LinkedHashMap<>();
		arancel.put("oferta", oferta);
		if (personaId != null) {
			arancel.put("persona_id", personaId);
		}
		partes.llamar("arancel_vigente", arancel);
		return partes.resultado();
	}
}
