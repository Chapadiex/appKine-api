package com.akine.organization.domain;

/**
 * Una fila de la lectura con la que se resuelve un contexto de trabajo: una membership activa de
 * la cuenta en la organizacion, junto con lo que hace falta saber de la sede, la organizacion y
 * la suscripcion para decidir si ese contexto se puede usar (docs/carga.md §13).
 *
 * <p>Las tres columnas que acompanan a la membership son las mismas en todas las filas de una
 * lectura y valen {@code null} cuando su condicion no se cumple:
 * <ul>
 *   <li>{@code consultorioId}: la sede pedida, solo si existe, esta activa y es <b>de esa
 *       organizacion</b>;</li>
 *   <li>{@code organizacionId}: la organizacion, solo si esta vigente;</li>
 *   <li>{@code estadoSuscripcion}: el estado de su suscripcion, si la tiene.</li>
 * </ul>
 *
 * <p>Es un valor de lectura para decidir un acceso, no para devolver: la membership es una
 * entidad desprendida y nadie la modifica (ver {@code LecturasDeAcceso}).
 */
public record LecturaDeContexto(
		Membership membership,
		Long consultorioId,
		Long organizacionId,
		SubscriptionStatus estadoSuscripcion) {
}
