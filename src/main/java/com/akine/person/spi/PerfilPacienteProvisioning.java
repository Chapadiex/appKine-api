package com.akine.person.spi;

/**
 * La activacion del perfil clinico de una Persona, ofrecida a otros modulos (RF-M07-008).
 *
 * <h2>Esto NO es una segunda clase que escriba en {@code perfil_paciente}</h2>
 *
 * <p>Y es la unica razon por la que este puerto puede existir. AKINE-03.01 dejo fijado que
 * <b>una sola clase del sistema convierte a alguien en paciente</b>:
 * {@code PerfilPacienteService}. No hay columna {@code es_paciente} que un {@code UPDATE}
 * distraido pueda poner en {@code true}, y {@code PacienteDirectory} advierte, textualmente, que
 * un modulo clinico capaz de crear pacientes por el camino de crear su historia seria RF-M07-010
 * violada desde afuera.
 *
 * <p>El unico implementador de este puerto <b>delega</b> en ese servicio. No tiene un
 * {@code INSERT}: tiene una llamada. Y como delega, hereda las tres cosas que importan: la
 * idempotencia de dos capas —pre-chequeo mas unique—, el permiso {@code paciente:manage} evaluado
 * sobre la sede del contexto, y la auditoria.
 *
 * <h2>Por que entonces existe, si RF-M07-010 desconfia de esto</h2>
 *
 * <p>Porque lo que RF-M07-010 prohibe es la conversion <b>implicita</b>, de costado, como efecto
 * de otra operacion. AKINE-08.04 la hace <b>explicita</b>: el comando de derivacion lleva una
 * bandera que el operador tiene que marcar, y sin ella una persona sin perfil es 409 con el
 * {@code personaId} en el problema para que la pantalla pueda ofrecer el paso. Derivar no convierte
 * a nadie en paciente por accidente.
 *
 * <p><b>Y si algun dia alguien saltea ese paso, el siguiente lo frena:</b>
 * {@code HistoriaClinicaDirectory.asegurar} exige perfil vigente desde 04.01. Son dos cerrojos y
 * los dos quedan.
 *
 * <h2>Este puerto SI autoriza</h2>
 *
 * <p>A diferencia de {@link PacienteDirectory}, que es una consulta. {@code PerfilPacienteService}
 * exige {@code paciente:manage} sobre la sede del contexto y eso <b>no se saltea por venir de otro
 * modulo</b>. Un profesional clinico que no gestione el padron recibe 403 en este paso, no en el
 * anterior: el mensaje distingue "no podes derivar" de "no podes dar de alta pacientes".
 */
public interface PerfilPacienteProvisioning {

	/**
	 * Activa el perfil de paciente si no lo tiene, y devuelve la persona con su perfil resuelto.
	 *
	 * <p>Idempotente: activar dos veces devuelve el mismo perfil y no audita de nuevo.
	 *
	 * @return el padron ya actualizado. {@code esPacienteVigente} siempre {@code true} al volver
	 */
	PacienteSnapshot asegurarPerfilVigente(ActivacionDePerfilPaciente activacion);
}
