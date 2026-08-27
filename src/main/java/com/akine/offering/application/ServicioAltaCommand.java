package com.akine.offering.application;

import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;

/**
 * Alta de un Servicio en el catalogo GLOBAL de la plataforma (RF-M27-001, RF-M06-006).
 *
 * <p>No lleva alcance —a diferencia de {@code CatalogoAltaCommand}— porque no hay alcance que
 * elegir: un Servicio es siempre global (RN-M27-001, ADR-0023). Tampoco lleva
 * {@code Idempotency-Key}, y es deliberado: un servicio de catalogo no consume cupo de ningun
 * plan, asi que lo unico que un reintento podria producir es una fila duplicada, y contra eso el
 * unique {@code uk_servicio_codigo_vigente} es una garantia mas fuerte que una clave —no depende
 * de que el cliente la mande ni de que la reuse bien—. El reintento responde 409 y no crea nada.
 * La contrapartida: despues de un timeout de red hay que releer el listado para saber si el alta
 * original entro. Mismo criterio, y mismo texto, que el alta del catalogo clinico en 02.05.
 *
 * <p><b>Los tres {@code *Default} son PROPUESTA, no regla.</b> Son el valor sugerido para cuando
 * un centro cree una Oferta sobre este servicio, y no la fuente de verdad de ninguna oferta ya
 * creada: RF-M06-006 —"los defaults no reemplazan la configuracion concreta de cada Oferta"— y
 * RN-M06-005. Ver el javadoc de {@code Servicio}.
 */
public record ServicioAltaCommand(

		/**
		 * Clave estable del servicio en el catalogo global. <b>Inmutable despues del alta</b>
		 * (ruling R3): es la referencia por la que otros lo nombran. Renombrar es cambiar
		 * {@link #nombre}.
		 */
		String codigo,

		String nombre,

		String descripcion,

		/**
		 * Clasificacion, nunca comportamiento (RN-M06-005). Obligatoria: la columna es
		 * {@code NOT NULL} y no existe un valor neutro que signifique "todavia no se sabe".
		 */
		Naturaleza naturaleza,

		/** Sugerencia de modalidad para las ofertas nuevas. Obligatoria, {@code NOT NULL} en V24. */
		Modalidad modalidadDefault,

		/** Sugerencia. Ausente se toma como {@code false}, que es el valor menos invasivo. */
		Boolean requiereCasoClinicoDefault,

		/** Sugerencia. Ausente se toma como {@code false}, que es el valor menos invasivo. */
		Boolean generaRegistroClinicoDefault) {
}
