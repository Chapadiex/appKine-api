package com.akine.organization.spi;

/**
 * Alta directa de una membership sobre una cuenta que YA existe.
 *
 * <p><b>Es una desviacion declarada de RF-M05-001 y RF-M05-002</b>, no un olvido. Esos
 * requerimientos piden un flujo de invitacion por correo (invitar, aceptar, rechazar, revocar);
 * la decision D-1 del usuario, del 23/08/2026, lo dejo fuera de AKINE-01.03. Sin algo que lo
 * reemplace la etapa quedaba <b>sin ninguna via para crear memberships</b>: al cierre, la unica
 * que una organizacion podria tener seria la del fundador, {@code colaborador:manage} se
 * evaluaria sobre un conjunto de una sola fila, y RN-M02-002 —roles distintos en sedes
 * distintas— quedaria soportada por el esquema y sin camino de usuario. Por eso entra el alta
 * directa: un administrador vincula a una cuenta existente, sin correo y sin aceptacion.
 *
 * <p><b>Lo que el alta directa NO reemplaza</b>, y viaja con RF-M05-001/002 a la etapa que los
 * reciba: no hay consentimiento de la persona vinculada, no hay alta de cuentas nuevas por esta
 * via, y no hay estado {@code INVITADA}.
 *
 * @param accountId      cuenta a vincular. <b>Ya tiene que existir.</b> Resolver un email a una
 *                       cuenta es trabajo de {@code identity}, que es su propietario:
 *                       {@code organization} no compila contra ese modulo y por eso este
 *                       contrato recibe el id ya resuelto y no un email
 * @param consultorioId  sede a la que se acota el vinculo, o {@code null} para alcance
 *                       organizacion
 * @param roleCode       rol de la matriz. {@code PLATFORM_ADMIN} no es un valor legal aqui
 *                       (ADR-0020)
 * @param reason         motivo declarado del alta. Obligatorio: sin el, la auditoria no puede
 *                       responder por que esa persona tiene acceso
 */
public record DirectMembershipCommand(
		long accountId, Long consultorioId, String roleCode, String reason) {
}
