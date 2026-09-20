package com.akine.activity.domain.port;

import com.akine.activity.domain.ClaseEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puertos de persistencia de {@code activity}.
 *
 * <p>Viven en {@code domain} y no en {@code infrastructure} porque {@code application} consume
 * puertos, nunca repositorios: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 *
 * <p><b>Ninguna firma resuelve por id pelado.</b> Todas llevan {@code organizationId} —y la sede
 * donde corresponde— en el {@code WHERE}: sin el predicado, datos de dos ambitos se mezclan bajo un
 * mismo id y el resultado no falla, inventa.
 */
public final class ActivityRepositoryPorts {

	private ActivityRepositoryPorts() {
	}

	public interface ClaseProgramadaRepositoryPort {

		ClaseProgramada save(ClaseProgramada clase);

		/**
		 * Guarda y <b>vacia la sesion en el acto</b>, para que la respuesta lleve la version nueva.
		 *
		 * <p>Con {@code save} a secas, el {@code @Version} lo incrementa Hibernate al vaciar la
		 * sesion, que ocurre al commitear: la vista se arma <b>antes</b> y sale con la version
		 * vieja. La base queda en 1 y el cliente se lleva un 0, manda esa version en la operacion
		 * siguiente y come un 409 que no le echa la culpa a nadie. Ya se pago con los turnos.
		 */
		ClaseProgramada saveAndFlush(ClaseProgramada clase);

		Optional<ClaseProgramada> findByIdInScope(
				long organizationId, long consultorioId, long claseId);

		/**
		 * La clase creada con esa clave de idempotencia, si existe.
		 *
		 * <p>Alcance ORGANIZACION y no sede, igual que el unique de {@code V58} y que el de los
		 * turnos: una clave reusada apuntando a otra sede sigue siendo la misma clave.
		 */
		Optional<ClaseProgramada> findByIdempotencyKey(long organizationId, String idempotencyKey);

		/**
		 * Clases vivas de un profesional que se <b>cruzan</b> con {@code [inicio, fin)}.
		 *
		 * <p>Se cruzan, no coinciden: una clase de 09:00 a 10:00 y un turno de 09:30 a 10:00 no
		 * comparten un solo valor de columna. Ningun unique puede expresarlo y MySQL 8.4 no tiene
		 * exclusion constraints; lo decide esta consulta, <b>bajo el lock de {@code agenda_sede}</b>.
		 */
		List<ClaseProgramada> findVivasDeProfesionalQueCruzan(
				long organizationId, long profesionalMembershipId, Instant inicio, Instant fin);

		/** Lo mismo para un espacio. Ver {@link #findVivasDeProfesionalQueCruzan}. */
		List<ClaseProgramada> findVivasDeEspacioQueCruzan(
				long organizationId, long espacioId, Instant inicio, Instant fin);

		/**
		 * Clases de una sede que empiezan dentro de {@code [desde, hasta)}, de la mas temprana a la
		 * mas tarde.
		 *
		 * <p><b>Trae tambien las canceladas</b>, a diferencia de las consultas de solapamiento: esta
		 * pregunta por que hay en la grilla, y alguien puede presentarse a una clase que se
		 * cancelo. Es el mismo criterio que {@code findDeLaSedeEnVentana} de los turnos.
		 */
		List<ClaseProgramada> findDeLaSedeEnVentana(
				long organizationId, long consultorioId, Instant desde, Instant hasta);

		/**
		 * Clases <b>vivas</b> de una sede en una ventana. Es lo que alimenta la exclusion y la
		 * proyeccion de ocupacion; las canceladas no ocupan nada.
		 */
		List<ClaseProgramada> findVivasDeLaSedeEnVentana(
				long organizationId, long consultorioId, Instant desde, Instant hasta);

		/**
		 * La clase, con la fila <b>bloqueada en exclusiva</b> hasta el commit.
		 *
		 * <p>Lo usan reprogramar y cancelar clase, y cierra el hueco que ni el lock de
		 * {@code agenda_sede} ni el {@code @Version} cubren: bajar la capacidad lee la ocupacion con
		 * un {@code SELECT} plano y escribe despues, asi que una inscripcion concurrente se mete en
		 * el medio y deja {@code cupo_ocupado > capacidad}. Con la fila bloqueada, el
		 * {@link #tomarCupo} de esa inscripcion espera y despues evalua su {@code LEAST} contra la
		 * capacidad NUEVA.
		 *
		 * <p><b>Orden de locks, que no se negocia:</b>
		 * {@code agenda_sede -> clase_programada -> inscripcion_clase}. Quien toma este lock ya
		 * tiene el de la agenda o no lo va a pedir nunca; tomarlos al reves es un deadlock en
		 * produccion que ningun test unitario reproduce.
		 */
		Optional<ClaseProgramada> lockByIdInScope(
				long organizationId, long consultorioId, long claseId);

		/**
		 * <b>Otorga un lugar.</b> Esta es la operacion que hace cumplir el cupo, y no hay otra.
		 *
		 * <p>Una fila afectada es "tenes el lugar"; <b>cero filas es "no hay lugar"</b>. Una sola
		 * sentencia, asi que no queda ninguna ventana entre leer y escribir: el {@code UPDATE} toma
		 * el lock exclusivo de la fila y lee la version commiteada mas reciente —current read—, de
		 * modo que la segunda transaccion evalua su predicado contra lo que la primera ya escribio.
		 *
		 * <p>Contar con un {@code SELECT} y despues insertar tiene una ventana, y <b>la ventana es
		 * el bug</b>: dos transacciones cuentan 7 de 8, las dos insertan y la clase queda con 9
		 * personas en 8 lugares sin que nada falle. Releer no salva: con {@code REPEATABLE READ} la
		 * segunda lectura devuelve la misma foto.
		 *
		 * <p>Es el mismo patron de {@code CobroRepository#descontarSaldo} (07.02) y de
		 * {@code AutorizacionRepository#descontarSaldo} (04.05).
		 *
		 * @param capacidadEfectiva {@code min(clase, oferta, espacio)} calculado al leer
		 *                          (RN-M28-002). Va como parametro y no como columna porque la
		 *                          capacidad de la oferta y la del box <b>son de otros modulos</b>:
		 *                          ninguna base puede comprobarlo sola. Por eso el {@code WHERE}
		 *                          lleva ademas la columna {@code capacidad}, que es el techo que
		 *                          ningun llamador puede saltearse
		 * @return 1 si se otorgo el lugar, 0 si no habia
		 */
		int tomarCupo(long organizationId, long claseId, int capacidadEfectiva);

		/**
		 * Devuelve un lugar. Condicional por el mismo motivo que {@link #tomarCupo}: un cupo
		 * ocupado negativo es tan imposible como un saldo negativo.
		 *
		 * <p><b>Y es donde se toma el lock que serializa la lista de espera.</b> Quien cancela
		 * libera ANTES de leer la cola, para que dos bajas simultaneas se serialicen aca y la
		 * segunda lea una cola de la que ya salio el promovido por la primera. Leer la cola antes
		 * de liberar es leer una foto vieja.
		 *
		 * @return 1 si se libero, 0 si el contador ya estaba en cero
		 */
		int liberarCupo(long organizationId, long claseId);

		/**
		 * Pone el contador en cero. <b>Solo para la cancelacion de la clase entera</b>, que cancela
		 * todas sus inscripciones de una: sin esto el contador quedaria contando recibos que ya no
		 * existen y el invariante dejaria de cerrar.
		 */
		int vaciarCupo(long organizationId, long claseId);

		/**
		 * Reserva la proxima posicion de la lista de espera y la devuelve.
		 *
		 * <p><b>{@code UPDATE ... ultima_posicion_espera + 1}, nunca {@code MAX + 1}.</b> Es la
		 * regla del repositorio —la misma de los correlativos de sesion, caso y comprobante— y el
		 * motivo es el mismo: {@code MAX + 1} repite numeros en cuanto hay dos altas a la vez, y
		 * sobre una tabla con cancelaciones los repite hasta sin concurrencia.
		 *
		 * <p>El contador <b>nunca baja</b>. Que la cola tenga huecos es correcto; que dos personas
		 * tengan la posicion 4 no lo es.
		 */
		int siguientePosicionDeEspera(long organizationId, long claseId);
	}

	/**
	 * Inscripciones de una clase (RN-M28-003).
	 *
	 * <p><b>Ninguna firma decide si hay lugar.</b> Eso es
	 * {@link ClaseProgramadaRepositoryPort#tomarCupo}, y tiene que seguir siendo el unico lugar: un
	 * metodo {@code contarOcupadas} usado para decidir —y no para diagnosticar— reintroduce la
	 * ventana entre leer y escribir que la etapa entera existe para cerrar.
	 */
	public interface InscripcionClaseRepositoryPort {

		InscripcionClase save(InscripcionClase inscripcion);

		/** Ver {@link ClaseProgramadaRepositoryPort#saveAndFlush}: la respuesta lleva la version nueva. */
		InscripcionClase saveAndFlush(InscripcionClase inscripcion);

		Optional<InscripcionClase> findByIdInScope(
				long organizationId, long claseId, long inscripcionId);

		/** La inscripcion creada con esa clave de idempotencia, si existe. Alcance ORGANIZACION. */
		Optional<InscripcionClase> findByIdempotencyKey(long organizationId, String idempotencyKey);

		/**
		 * La inscripcion <b>viva</b> de esa persona en esa clase, si existe.
		 *
		 * <p>Da el 409 explicable de "ya esta anotada". El unique
		 * {@code uk_inscripcion_clase_persona} cubre la carrera entre dos altas simultaneas; esta
		 * consulta cubre el caso normal con un mensaje que nombra el problema. Hacen falta las dos.
		 */
		Optional<InscripcionClase> findVivaDePersona(
				long organizationId, long claseId, long personaId);

		/** Todas las de una clase, vivas y canceladas, en orden de estado y posicion. */
		List<InscripcionClase> findDeLaClase(long organizationId, long claseId);

		/** Las que consumen cupo o esperan: lo que hay que avisar si la clase se mueve. */
		List<InscripcionClase> findVivasDeLaClase(long organizationId, long claseId);

		/**
		 * La cabeza de la cola: la que espera hace mas tiempo.
		 *
		 * <p>Ordena por {@code (posicion_espera, id)}. El {@code id} al final no es decorativo:
		 * hace el orden <b>total</b>, que es lo que "reproducible" significa en CA-M28-004-06.
		 *
		 * <p><b>Se consulta SIEMPRE despues de haber liberado el lugar</b>, nunca antes: ver
		 * {@link ClaseProgramadaRepositoryPort#liberarCupo}.
		 */
		Optional<InscripcionClase> siguienteEnEspera(long organizationId, long claseId);

		/** Cuantas esperan. Es informativo —RF-M12-010— y no decide nada. */
		int contarEnEspera(long organizationId, long claseId);

		/**
		 * Promueve una inscripcion de la cola a un lugar real.
		 *
		 * <p><b>Condicional, y el cero es la mitad del punto.</b>
		 * {@code WHERE estado = 'LISTA_ESPERA'} devolviendo cero filas significa "ya la promovio
		 * otro". Hoy el lock de la fila de la clase ya lo garantiza; esto es defensa en profundidad
		 * para cualquier camino futuro que promueva sin pasar por la liberacion —una pantalla de
		 * "ofrecer la vacante a mano", un job—. Una promocion doble no es cosmetica: es una persona
		 * a la que se le prometio un lugar que no existe.
		 *
		 * @return 1 si esta llamada la promovio, 0 si ya no estaba en la cola
		 */
		int promover(long organizationId, long inscripcionId, Instant ahora);

		/**
		 * Cancela de una sola vez todas las inscripciones vivas de una clase que se cancelo
		 * (RF-M28-006 paso 6, que 08.01 difirio a esta etapa).
		 *
		 * <p>Una sentencia y no un bucle: la cantidad de participantes no acota, y N
		 * {@code UPDATE} dentro de la transaccion que cancela la clase la alargan en proporcion al
		 * exito de la clase. <b>No devuelve creditos ni plata</b>: eso es 08.07.
		 *
		 * @return cuantas se cancelaron
		 */
		int cancelarTodasPorClaseCancelada(
				long organizationId, long claseId, String motivo, long cuentaId, Instant ahora);
	}

	/**
	 * Historial de transiciones (RN-M28-009). <b>Solo escribe e itera: no actualiza ni borra.</b>
	 *
	 * <p>La ausencia de {@code delete} y de {@code update} en este contrato no es un olvido: es la
	 * unica garantia real de que el historial sea inmutable. Misma decision que
	 * {@code TurnoEventoRepositoryPort} y que {@code platform.spi.audit.AuditTrail}.
	 */
	public interface ClaseEventoRepositoryPort {

		ClaseEvento registrar(ClaseEvento evento);

		/** Los eventos de una clase, del mas viejo al mas nuevo. */
		List<ClaseEvento> historial(long organizationId, long claseId);
	}
}
