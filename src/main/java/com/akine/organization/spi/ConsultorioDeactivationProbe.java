package com.akine.organization.spi;

import java.time.Instant;

/**
 * Un modulo que registra hechos sobre una sede declara aca si algo bloquea su baja.
 *
 * <h2>Por que un puerto invertido y no una consulta directa</h2>
 *
 * <p>El unico bloqueo previsto son los turnos futuros, y {@code scheduling} no existe todavia
 * (llega en F5, M12). Que {@code organization} consultara turnos violaria la regla 1 de
 * {@code AGENT.md} seccion 4 —cada tabla tiene un modulo propietario— y ademas dibujaria una
 * flecha {@code organization -> scheduling}, o sea del cimiento hacia el consumidor: ciclo
 * garantizado, y ArchUnit lo rechazaria.
 *
 * <p>Invertido, la flecha va {@code scheduling -> organization.spi}: permitida, unidireccional
 * y ya probada — es el mismo patron de {@code platform.spi.tenant.MembershipDirectory},
 * declarado por {@code platform} e implementado por {@code organization.infrastructure.tenant}.
 *
 * <p><b>En F1 la lista de implementaciones es VACIA, y la baja siempre procede.</b> Eso no es
 * simular funcionalidad: es declarar la forma para que agregarla en F5 sea sumar una clase, no
 * rediseñar la baja. El codigo {@code 409 consultorio-has-active-references} se reserva en el
 * contrato desde ya para que su aparicion no sea un cambio de comportamiento sorpresivo para el
 * frontend.
 *
 * <p>Que debe pasar exactamente con los turnos ya reservados es una decision abierta (D-8):
 * RN-M03-003 solo dice que una sede inactiva no recibe turnos NUEVOS, y ADR-0011 (DP-04)
 * prohibe una cancelacion en cascada sin confirmacion explicita, motivo y auditoria por turno.
 */
public interface ConsultorioDeactivationProbe {

	/**
	 * Referencias vigentes que un modulo declara sobre una sede.
	 *
	 * @param type  vocabulario del modulo que responde, p.ej. {@code turnos-futuros}. Viaja al
	 *              cliente dentro del Problem Details, asi que es un valor estable y sin datos
	 *              de personas
	 * @param count cuantas hay. Cero significa que nada bloquea
	 */
	record ActiveReferences(String type, long count) {

		/** La respuesta de un modulo que no tiene nada que objetar. */
		public static ActiveReferences ninguna() {
			return new ActiveReferences(null, 0L);
		}

		public boolean bloquean() {
			return count > 0;
		}
	}

	/**
	 * Responde si el modulo tiene hechos vigentes sobre esa sede que impidan darla de baja.
	 *
	 * <p>Se invoca DENTRO de la transaccion de la baja y despues del bloqueo del tenant, con lo
	 * que la respuesta no puede quedar vieja entre la consulta y el commit.
	 *
	 * @param at instante contra el que se evalua la vigencia (UTC)
	 */
	ActiveReferences activeReferencesOn(long organizationId, long consultorioId, Instant at);
}
