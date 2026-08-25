package com.akine.resource.spi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lo que otro modulo puede saber de un concepto del catalogo clinico (M06).
 *
 * <p>Es lo que la etapa AKINE-02.05 deja disponible para la siguiente: <b>ids, codigos y
 * versiones</b> de especialidad, practica y nomenclador. Los consumidores previstos son
 * {@code clinical} (M14, que registra que practica se realizo en una sesion) y
 * {@code contracting} (M16, que arma convenios sobre codigos con vigencia).
 *
 * <h2>Lo que un consumidor tiene que guardar, y lo que no</h2>
 *
 * <p>Un hecho historico —una sesion, una linea de convenio— guarda el <b>id</b> y, si necesita
 * ser legible sin resolver nada, tambien el <b>codigo y el nombre del momento</b>. Lo que
 * <b>no</b> debe hacer es guardar solo el nombre y resolverlo despues por texto: los nombres
 * cambian y los renombres son parte de la historia (RN-M06-002).
 *
 * <h2>{@code vigente} contra {@code activo}</h2>
 *
 * <p>{@code activo} es el ciclo de vida; {@code vigente} dice si el concepto se puede
 * <b>elegir</b> en el instante consultado. Un modulo que ofrece un selector filtra por
 * {@code vigente}; uno que muestra un historico no filtra por nada — un concepto dado de baja
 * <b>sigue resolviendo</b>, y eso es RN-M06-001.
 */
public record CatalogoSnapshot(

		long id,

		/** {@code null} cuando el concepto es global de plataforma. */
		Long organizationId,

		String codigo,

		String name,

		Instant validFrom,

		Instant validUntil,

		boolean activo,

		boolean vigente,

		/** Solo en practicas. */
		Long especialidadId,

		/** Solo en vigencias de nomenclador: el valor publicado para esa ventana. */
		BigDecimal valorReferencia,

		long version) {

	/** {@code true} si el concepto es del catalogo comun y lo ve todo el SaaS. */
	public boolean esGlobal() {
		return organizationId == null;
	}
}
