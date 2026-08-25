package com.akine.organization.spi;

/**
 * Lo minimo que otro modulo necesita saber de una organizacion.
 *
 * <p>Tres campos y ninguno mas: el nombre, para redactarle un correo a alguien que todavia no
 * pertenece al tenant, y si esta vigente. Todo lo demas —plan, suscripcion, slug, timezone— es
 * de {@code organization} y no tiene por que cruzar el borde.
 */
public record OrganizationSnapshot(long id, String name, boolean active) {
}
