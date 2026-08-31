package com.akine.clinical.spi;

import java.util.List;

/**
 * Aporte de un modulo al timeline clinico de una Historia Clinica.
 *
 * <h2>Que hay hoy: la costura, no el timeline</h2>
 *
 * <p>El timeline clinico es AKINE-04.02 y esta <b>cortada</b> por DP-10. Lo que queda es la
 * costura, y queda representada asi —una interfaz con consumidor real y cero implementaciones—
 * porque DP-10 pide que las capacidades diferidas esten en el modelo y no comentadas en el codigo.
 *
 * <p>El consumidor inyecta una {@code List<EventoClinicoContributor>}, que Spring resuelve como
 * lista vacia mientras nadie la implemente. <b>Por eso esta es lista y no bean singular</b>, al
 * reves que {@link RelacionAsistencialProbe}: el timeline es por definicion un agregado de varias
 * fuentes —sesiones, adjuntos, ordenes, turnos— y "cero eventos" es una respuesta valida que no
 * necesita una implementacion por defecto que la produzca.
 *
 * <p>Deliberadamente <b>no</b> hay una tabla vacia esperando al timeline. Una tabla sin escritor
 * es peor que ninguna: invita a que alguien la llene con un formato que 04.02 despues no puede
 * usar. El ancla del timeline futuro es {@code historia_clinica.id}, que ya existe y ya es
 * estable.
 */
public interface EventoClinicoContributor {

	/**
	 * Los eventos que este modulo aporta al timeline de esa historia, mas nuevos primero.
	 *
	 * @param limite tope de eventos a devolver. Cada contribuyente lo respeta por su cuenta: el
	 *               consumidor mezcla y vuelve a recortar
	 */
	List<EventoClinico> eventosDe(long organizationId, long historiaClinicaId, int limite);
}
