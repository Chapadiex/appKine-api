package com.akine.knowledge.api;

import com.akine.knowledge.api.dto.ConsultaEventoAclaracion;
import com.akine.knowledge.api.dto.ConsultaEventoError;
import com.akine.knowledge.api.dto.ConsultaEventoEspera;
import com.akine.knowledge.api.dto.ConsultaEventoEtapa;
import com.akine.knowledge.api.dto.ConsultaEventoFin;
import com.akine.knowledge.api.dto.ConsultaEventoFuentes;
import com.akine.knowledge.api.dto.ConsultaEventoTexto;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publica como componentes los esquemas de los eventos del stream de {@code consultarBiblioteca}
 * (y, por referencia, {@code FuenteDeConsultaResponse} y {@code OpcionDeAclaracionResponse}). El 200 es {@code text/event-stream} con
 * esquema {@code string}, asi que springdoc no los alcanza desde la operacion; sin esto el cliente
 * generado no tendria sus tipos.
 */
@Component
class ConsultaEventosEnElContrato implements OpenApiCustomizer {

	static final List<Class<?>> EVENTOS = List.of(
			ConsultaEventoEspera.class,
			ConsultaEventoFuentes.class,
			ConsultaEventoTexto.class,
			ConsultaEventoFin.class,
			ConsultaEventoError.class,
			ConsultaEventoAclaracion.class,
			ConsultaEventoEtapa.class);

	@Override
	public void customise(io.swagger.v3.oas.models.OpenAPI openApi) {
		if (openApi.getComponents() == null) {
			openApi.setComponents(new Components());
		}
		for (Class<?> evento : EVENTOS) {
			ModelConverters.getInstance(true).readAll(new AnnotatedType(evento))
					.forEach((nombre, esquema) -> openApi.getComponents().addSchemas(nombre, esquema));
		}
	}
}
