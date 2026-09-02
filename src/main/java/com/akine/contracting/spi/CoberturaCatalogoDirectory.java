package com.akine.contracting.spi;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lectura del catalogo de financiadores y planes para modulos que no son duenos de M15.
 *
 * <p><b>No autoriza nada.</b> Es una costura entre modulos, no una API: quien la llama ya resolvio
 * pertenencia y permiso con su propio criterio. {@code convenio:manage} es de quien administra el
 * catalogo, no de quien registra la cobertura de un paciente, asi que una llamada aca nunca
 * sustituye a un control de acceso. Mismo contrato, y misma advertencia, que
 * {@code OfertaDirectory}.
 *
 * <p>Todas las firmas llevan {@code organizationId} en el {@code WHERE}: no hay financiador global
 * en 03.03 y una fila de otro tenant no resuelve nunca.
 *
 * <h2>Las dos mitades de esta interfaz hacen cosas OPUESTAS</h2>
 *
 * <pre>
 *   find* / listar   LECTURA VIVA. Para DECIDIR y para poblar un selector. Refleja el estado
 *                    de hoy, y por eso cambia cuando alguien edita el plan.
 *
 *   congelar         COPIA CONGELADA. Para GUARDAR. Devuelve ReferenciaDeCobertura, que el
 *                    consumidor escribe en sus propias columnas y no vuelve a pedir.
 * </pre>
 *
 * <p>Confundirlas es el defecto que 03.04 y 03.05 tienen que no cometer: guardar un
 * {@code planId} y resolver el nombre al mostrar hace que renombrar un plan reescriba
 * retroactivamente coberturas ya firmadas. Ver {@link ReferenciaDeCobertura}.
 */
public interface CoberturaCatalogoDirectory {

	/** El financiador de ese tenant, operable o no. {@code empty} si no existe o es ajeno. */
	Optional<FinanciadorSnapshot> findFinanciador(long organizationId, long financiadorId);

	/**
	 * El plan de ese tenant, operable o no y vigente o no.
	 *
	 * <p>Devuelve tambien los dados de baja y los vencidos <b>a proposito</b>: RN-M15-003 exige
	 * que los historicos sigan resolviendo, y responder {@code empty} para un plan viejo seria
	 * borrar historia por la puerta de atras. Quien tiene que decidir si se puede ELEGIR pregunta
	 * {@link PlanCoberturaSnapshot#seleccionableEl(LocalDate)}, que es una pregunta distinta.
	 */
	Optional<PlanCoberturaSnapshot> findPlan(long organizationId, long planId);

	/**
	 * Los planes de un financiador que se pueden ELEGIR ese dia (RN-M15-002).
	 *
	 * <p>Es lo que puebla el selector de M08. Ya viene filtrado —financiador operable, plan
	 * operable y vigente ese dia— porque dejar el filtro del lado del llamador garantiza que
	 * antes o despues alguien lo olvide y ofrezca un plan de baja en una alta nueva.
	 */
	List<PlanCoberturaSnapshot> planesSeleccionables(
			long organizationId, long financiadorId, LocalDate fecha);

	/**
	 * Congela la referencia al plan para que el consumidor la COPIE a sus propias columnas.
	 *
	 * <p>Devuelve {@code empty} cuando el plan no se puede elegir ese dia —no existe, es de otro
	 * tenant, esta dado de baja, su financiador esta dado de baja, o la fecha cae fuera de su
	 * vigencia—. <b>Que el rechazo sea la ausencia de valor y no una excepcion es deliberado</b>:
	 * quien firma una cobertura tiene que decidir que responder (400, 409 o un mensaje en la
	 * pantalla) y esa decision es suya, no de este modulo.
	 *
	 * <p>Lo devuelto ya no depende de esta interfaz: es una copia. Volver a llamar mañana puede
	 * dar otro resultado, y esa es exactamente la razon por la que el consumidor tiene que
	 * guardarlo.
	 *
	 * @param fecha dia contra el que se evalua la vigencia, en la zona local de quien opera.
	 *              Explicito y nunca un reloj implicito, misma regla que {@code PermissionQuery.at}
	 */
	Optional<ReferenciaDeCobertura> congelar(long organizationId, long planId, LocalDate fecha);
}
