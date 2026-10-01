package com.akine.offering.application;

import com.akine.offering.domain.PoliticaDeDevengo;
import com.akine.offering.domain.Modalidad;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de una Oferta: COMO esta sede concreta va a prestar un {@code Servicio} global
 * (RF-M03-006, RF-M03-007, RF-M27-003).
 *
 * <h2>Lo que este comando NO lleva, y por que</h2>
 *
 * <p><b>Ni {@code organizationId} ni {@code consultorioId}.</b> Los dos salen del contexto que
 * {@code TenantContextFilter} ya revalido contra la base en este request, nunca de un campo del
 * cuerpo: aceptarlos aca le permitiria a un {@code CONSULTORIO_ADMIN} de la sede A crear ofertas
 * en la B escribiendo otro numero. Viajan como parametros del metodo, que es donde el llamador
 * los compara contra el actor.
 *
 * <p><b>Tampoco {@code Idempotency-Key}</b>, mismo razonamiento textual que el alta de espacio y
 * la de servicio: una oferta no consume cupo de ningun plan, asi que lo unico que un reintento por
 * timeout podria producir es una fila duplicada, y contra eso el unique
 * {@code uk_oferta_sede_nombre_vigente} es una garantia mas fuerte que una clave —no depende de
 * que el cliente la mande ni de que la reuse bien—. La contrapartida, dicha y no tapada: tras un
 * timeout de red el cliente recibe 409 en vez del recurso creado y tiene que releer el listado
 * para saber si el alta original entro.
 *
 * <h2>Los tres campos que se pueden omitir para copiar el default del Servicio</h2>
 *
 * <p>{@link #modalidad}, {@link #requiereCasoClinico} y {@link #generaRegistroClinico} son
 * nulables, y ausentes toman el {@code *Default} del {@code Servicio} elegido. <b>Eso es una copia
 * de una sola vez, no un vinculo</b>: RF-M06-006 exige que los defaults no reemplacen la
 * configuracion concreta de cada Oferta, y una vez copiados son datos de esta oferta con su propio
 * ciclo de vida. Cambiar el default del Servicio despues no toca esta fila — ver el javadoc de
 * {@code OfertaServicioConsultorio}.
 *
 * <p>{@link #requiereProfesional} y {@link #requiereEspacio} NO tienen default en el Servicio: son
 * decisiones puramente operativas de la sede (que persona atiende, en que box) y el catalogo
 * global no tiene como opinar sobre ellas. Ausentes se toman como {@code false}, el valor menos
 * invasivo.
 */
public record OfertaAltaCommand(

		/**
		 * Que concepto del catalogo global materializa esta oferta (regla maestra 14).
		 * <b>Inmutable despues del alta</b> (ruling R3, {@code updatable = false} en la entidad):
		 * mover una oferta a otro servicio no es editarla, es otra oferta.
		 */
		Long servicioId,

		/**
		 * Lo que ve el paciente en la cartelera. Texto libre para MOSTRAR, nunca para decidir:
		 * RN-M06-006 prohibe condicionales por nombres como Pilates, RPG, Yoga u Osteopatia
		 * (regla maestra 15). Lo que decide son los campos tipados de este mismo comando.
		 */
		String nombreComercial,

		String descripcion,

		/** Ausente copia {@code Servicio.modalidadDefault}. GRUPAL exige {@link #capacidad} &gt; 1. */
		Modalidad modalidad,

		/** Obligatoria y mayor a cero. No hay default en el Servicio: la duracion es de la sede. */
		Integer duracionMinutos,

		/** Obligatoria y mayor a cero. Con {@link #modalidad} GRUPAL, mayor a uno. */
		Integer capacidad,

		/** Viaja junto con {@link #moneda}: los dos o ninguno ({@code ck_oferta_precio_con_moneda}). */
		BigDecimal precioBase,

		String moneda,

		/**
		 * Como y cuando cobra la oferta (RN-M27-006). {@code null} = el centro no lo declara.
		 *
		 * <p>02.06 escribio aca que "se guarda y se muestra, nadie lo interpreta", porque los
		 * modulos que definirian su vocabulario no existian. Desde AKINE-08.06 <b>si se
		 * interpreta</b>: es lo que decide cuando nace la deuda de esta oferta. Ver
		 * {@link PoliticaDeDevengo}.
		 */
		PoliticaDeDevengo politicaDeDevengo,

		/** Ausente se toma como {@code false}. */
		Boolean admiteObraSocial,

		/** Ausente copia {@code Servicio.requiereCasoClinicoDefault}. */
		Boolean requiereCasoClinico,

		/** Ausente copia {@code Servicio.generaRegistroClinicoDefault}. */
		Boolean generaRegistroClinico,

		/** Sin default en el Servicio: es operativo de la sede. Ausente, {@code false}. */
		Boolean requiereProfesional,

		/** Sin default en el Servicio: es operativo de la sede. Ausente, {@code false}. */
		Boolean requiereEspacio,

		/** Inicio de la ventana operativa. Ausente se toma HOY en la zona del servidor. */
		LocalDate vigenciaDesde,

		/** Limite EXCLUSIVO. {@code null} = sin fin previsto, que es un estado real (RN-M27-006). */
		LocalDate vigenciaHasta) {
}
