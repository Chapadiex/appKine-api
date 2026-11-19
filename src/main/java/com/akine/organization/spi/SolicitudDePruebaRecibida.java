package com.akine.organization.spi;

/**
 * Evento: llego un pedido de prueba gratis desde la landing (18/11/2026). Lo escucha
 * {@code identity} para avisarle a la plataforma por correo: {@code organization} no puede
 * encolar notificaciones sin cerrar un ciclo con {@code notification}.
 *
 * <p>Se publica dentro de la transaccion que guarda el pedido. Sin datos de contacto: esos se
 * leen en la bandeja.
 *
 * @param servicios texto legible de los servicios pedidos, por ejemplo "Kinesiologia y Actividades"
 * @param sedes     texto legible de cuantas sedes declaro, por ejemplo "Hasta 3"
 */
public record SolicitudDePruebaRecibida(long solicitudId, String nombreCentro, String servicios, String sedes) {
}
