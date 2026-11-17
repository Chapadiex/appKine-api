package com.akine.encounter.domain.evolucion;

/**
 * El interruptor de M31 y sus umbrales, ya resueltos de la configuracion del despliegue.
 *
 * <p>Vive en {@code domain} para que {@code application} lo reciba sin depender de
 * {@code infrastructure}, igual que {@code OutboxWorkerSettings} en {@code notification}.
 *
 * @param habilitada RN-M31-010: apagada, la operacion responde 404
 */
public record ConfiguracionDeEvolucion(boolean habilitada, UmbralesDeEvolucion umbrales) {
}
