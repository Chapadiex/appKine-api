package com.akine.organization.spi;

/**
 * Contexto activo de una cuenta: la ultima seleccion, ya revalidada.
 *
 * <p>Solo se construye despues de comprobar que el contexto sigue siendo valido. Un puntero
 * obsoleto —membership vencida o revocada, consultorio dado de baja, suscripcion cancelada—
 * nunca llega a existir como {@code ActiveContext}: se devuelve {@code Optional.empty()}
 * (D-7). Asi el consumidor no puede confundir "lo que el usuario eligio la vez pasada" con
 * "lo que el usuario puede usar hoy".
 */
public record ActiveContext(long organizationId, long consultorioId) {
}
