package com.akine.assistant.spi;

import java.util.Map;

/** Una herramienta que el agente puede invocar. Cada una vive en el modulo dueno del dato. */
public interface HerramientaDelAgente {

	/** Nombre en snake_case, estable. */
	String nombre();

	String descripcion();

	/** JSON Schema de la entrada, con {@code additionalProperties:false}. */
	String esquemaEntrada();

	/** Si el actor puede ver esta herramienta; las que no, no se le ofrecen al modelo. */
	default boolean disponiblePara(ActorDelAsistente actor) {
		return true;
	}

	ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada, ContextoDeConsulta ctx);
}
