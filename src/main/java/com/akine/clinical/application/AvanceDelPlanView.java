package com.akine.clinical.application;

import java.util.List;

/**
 * El avance de un plan contra una version concreta de su contenido (RF-M11-005).
 *
 * <h2>Por que la version viaja en la respuesta y no es opcional</h2>
 *
 * <p>Porque los items cuelgan de la version, asi que el avance <b>"8 de 20" de la version 1 y el
 * "8 de 24" de la version 2 son dos numeros distintos y los dos correctos</b> (challenge seccion
 * 8.1). Una respuesta que no dijera de cual habla obligaria a la pantalla a adivinar, y la primera
 * que adivine mal va a mostrar un avance que no le corresponde a ningun plan.
 *
 * <h2>{@code completo} avisa. No cierra nada</h2>
 *
 * <p>RN-M11-004 lo prohibe explicitamente: completar la cantidad estimada <b>no</b> finaliza el plan
 * ni cierra el caso. El sistema avisa y la decision es clinica. Es la tentacion que mas se parece a
 * una mejora de producto y por eso es la mas peligrosa.
 *
 * @param numeroVersion version contra la que se conto. La vigente si no se pidio otra
 * @param completo      todos los items alcanzaron su cantidad planificada. Un plan sin items no
 *                      esta completo: no hay nada planificado contra lo cual estarlo
 */
public record AvanceDelPlanView(
		long planTratamientoId,
		long casoClinicoId,
		String estado,
		int numeroVersion,
		List<AvanceDeItemView> items,
		boolean completo) {

	public AvanceDelPlanView {
		items = items == null ? List.of() : List.copyOf(items);
	}
}
