package com.akine.organization.spi;

import java.time.Instant;
import java.util.Set;

/**
 * Que cuentas tienen el rol de plataforma vigente (ADR-0020).
 *
 * <p>Existe para el bootstrap del administrador de plataforma (DP-14, AKINE-A-4): {@code identity}
 * necesita saber si alguna de esas cuentas ya tiene credencial, y las dos mitades de la pregunta
 * tienen dueños distintos — el rol es de {@code organization}, la credencial de {@code identity}.
 * La flecha {@code identity -> organization.spi} ya existe; la inversa esta prohibida.
 *
 * <p><b>No es un listado para HTTP.</b> ADR-0020 prohibe que una ruta exponga quien administra la
 * plataforma; este puerto lo consume un runner de arranque, sin request ni actor. Devuelve ids y
 * nada mas: ni la entity, ni motivo, ni quien lo otorgo.
 */
public interface PlatformAdminRoster {

	/**
	 * Ids de cuenta con un rol de plataforma activo y vigente en ese instante.
	 *
	 * @param at instante contra el que se evalua la vigencia (UTC)
	 */
	Set<Long> cuentasConRolDePlataforma(Instant at);
}
