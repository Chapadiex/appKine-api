package com.akine.encounter.spi;

import java.util.Optional;

/**
 * Como se llama la clase de la que salio una atencion de origen grupal (AKINE-08.05, pedido de
 * web #104): la ficha de la Sesion muestra "Pilates terapeutico, martes 18 h" y no un id.
 *
 * <p><b>La interfaz vive aca y la implementa {@code activity}</b>, que es duenia de la clase:
 * {@code encounter -> activity} cerraria el ciclo con {@code activity -> encounter.spi}, la arista
 * de 08.05. Mismo patron que {@code clinical.spi.AtencionesDeLaDerivacionProbe}: el que pregunta
 * declara el contrato y no sabe quien responde.
 *
 * <p>No autoriza nada: quien llega aca ya leyo la Sesion con sus permisos, y el nombre de una clase
 * no es un dato clinico.
 */
public interface TituloDeClaseGrupal {

	/**
	 * El titulo de la clase, o el nombre comercial de su oferta si la clase no tiene titulo —el
	 * mismo criterio con el que se la lista—. Vacio si la clase no existe en esa sede.
	 */
	Optional<String> de(long organizationId, long consultorioId, long claseId);
}
