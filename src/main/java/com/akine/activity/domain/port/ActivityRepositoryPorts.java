package com.akine.activity.domain.port;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.AsistenciaEvento;
import com.akine.activity.domain.ClaseEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.ProductoServicio;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
		 * La clase en modo <b>compartido</b> ({@code FOR SHARE}): lo que toma el registro de
		 * asistencia antes de tocar la inscripcion o un pase (AKINE-08.07). Varias asistencias de la
		 * misma clase no se estorban entre si, pero ninguna corre a la vez que la cancelacion —que
		 * toma {@link #lockByIdInScope}— y por eso la devolucion de creditos de la cancelacion ve
		 * todo consumo de esa clase, y ningun consumo entra sobre una clase ya cancelada.
		 *
		 * <p>No sirve para quien despues escribe la fila de la clase (ingreso sin inscripcion, que
		 * toma cupo): dos S que piden X son un deadlock. Ese camino toma el exclusivo de entrada.
		 */
		Optional<ClaseProgramada> lockCompartidoByIdInScope(
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
		 * {@link #tomarCupo} para una clase {@code EN_CURSO}: el lugar del ingreso sin inscripcion
		 * (RF-M13-007, AKINE-08.03). Mismo {@code UPDATE} condicional y mismo techo; lo unico que
		 * cambia es el estado que exige el {@code WHERE}. Con el de {@link #tomarCupo}
		 * —{@code PROGRAMADA}— el ingreso devolvia cero filas siempre y respondia
		 * {@code clase-completa} con lugares libres (escenario 56 de {@code docs/tests-diferidos.md}).
		 *
		 * @return 1 si se otorgo el lugar, 0 si no habia
		 */
		int tomarCupoEnCurso(long organizationId, long claseId, int capacidadEfectiva);

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

		/**
		 * Las inscripciones con lugar que <b>todavia no tienen asistencia registrada</b>
		 * (AKINE-08.03). Es lo que el cierre operativo resuelve como ausentes.
		 *
		 * <p><b>El filtro va en la consulta y no en el servicio</b>, y de ahi sale la idempotencia
		 * del cierre: un segundo cierre no encuentra pendientes porque el primero les creo la fila,
		 * y {@code uk_asistencia_clase_persona} hace imposible una segunda. Traer todas y filtrar
		 * en memoria funcionaria igual hoy y se romperia el dia que la clase tenga doscientos
		 * participantes.
		 */
		List<InscripcionClase> findConLugarSinAsistencia(long organizationId, long claseId);

		/**
		 * Cancela las que quedaron en la cola cuando la clase ya se realizo (AKINE-08.03).
		 *
		 * <p>Dejarlas esperando una clase que ya ocurrio es un estado que no se resuelve nunca.
		 * <b>No libera cupo</b> —quien espera nunca lo tuvo, RN-M28-005— y <b>no devuelve creditos
		 * ni plata</b>: eso es 08.07.
		 *
		 * @return cuantas se cancelaron
		 */
		int cancelarEsperaPorClaseCerrada(
				long organizationId, long claseId, String motivo, long cuentaId, Instant ahora);

		/** Las inscripciones de una clase por sus ids, en un solo viaje. La usa el lote. */
		List<InscripcionClase> findDeLaClasePorIds(
				long organizationId, long claseId, List<Long> inscripcionIds);
	}

	/**
	 * Asistencias de una clase (AKINE-08.03, RF-M28-007).
	 *
	 * <p><b>Ninguna firma borra.</b> Una asistencia no se da de baja: se corrige, y la correccion
	 * apendea en {@link AsistenciaEventoRepositoryPort}. Si existiera un {@code delete}, el unique
	 * {@code (organization_id, clase_id, persona_id)} —la referencia economica unica— dejaria de
	 * significar "a lo sumo un hecho por participante".
	 */
	public interface AsistenciaActividadRepositoryPort {

		/** Ver {@link ClaseProgramadaRepositoryPort#saveAndFlush}: la respuesta lleva la version nueva. */
		AsistenciaActividad saveAndFlush(AsistenciaActividad asistencia);

		/** Guarda varias de una, para el cierre operativo. */
		List<AsistenciaActividad> saveAll(List<AsistenciaActividad> asistencias);

		Optional<AsistenciaActividad> findByIdInScope(
				long organizationId, long claseId, long asistenciaId);

		/**
		 * La asistencia de una inscripcion, si ya se registro.
		 *
		 * <p><b>Es el control de idempotencia entero de la etapa</b>, y por eso no hay
		 * {@code Idempotency-Key}: la clave natural del hecho ya esta en un unique. Si vuelve
		 * vacia se registra; si vuelve con el mismo resultado se devuelve sin escribir; si vuelve
		 * con otro, es una correccion y exige motivo.
		 */
		Optional<AsistenciaActividad> findDeInscripcion(long organizationId, long inscripcionId);

		/**
		 * La misma asistencia, <b>bloqueada</b> ({@code FOR UPDATE}) hasta el fin de la transaccion.
		 *
		 * <p>Es el lock que serializa dos derivaciones simultaneas del mismo participante
		 * (AKINE-08.04, {@code DerivacionConcurrenteIT}). Sin el, las dos pasaban el pre-chequeo de
		 * {@code clinical}, las dos intentaban el INSERT y el perdedor chocaba contra el unique con
		 * la sesion de Hibernate ya rota: un 500. Con el, la segunda espera a la primera y, en
		 * {@code READ_COMMITTED}, su pre-chequeo ya ve la fila de la ganadora y la devuelve.
		 */
		Optional<AsistenciaActividad> findDeInscripcionParaDerivar(long organizationId, long inscripcionId);

		/** Todas las de una clase, en orden de id. Alimenta el detalle operativo. */
		List<AsistenciaActividad> findDeLaClase(long organizationId, long claseId);

		/** Cuantas de la clase tienen un resultado que dice que la persona estuvo. */
		int contarPresentes(long organizationId, long claseId);

		/** Cuantas de la clase estan marcadas como ausentes. */
		int contarAusentes(long organizationId, long claseId);
	}

	/**
	 * Historial de una asistencia (RN-M28-009). <b>Solo escribe e itera: no actualiza ni borra.</b>
	 *
	 * <p>La ausencia de {@code update} y de {@code delete} en este contrato no es un olvido: es la
	 * unica garantia real de que el historial sea inmutable. Misma decision que
	 * {@link ClaseEventoRepositoryPort}.
	 */
	public interface AsistenciaEventoRepositoryPort {

		AsistenciaEvento registrar(AsistenciaEvento evento);

		/** Varios de una, para el cierre operativo. */
		List<AsistenciaEvento> registrarTodos(List<AsistenciaEvento> eventos);

		/** Los eventos de una asistencia, del mas viejo al mas nuevo. */
		List<AsistenciaEvento> historial(long organizationId, long asistenciaId);
	}

	/**
	 * Historial de transiciones (RN-M28-009). <b>Solo escribe e itera: no actualiza ni borra.</b>
	 *
	 * <p>La ausencia de {@code delete} y de {@code update} en este contrato no es un olvido: es la
	 * unica garantia real de que el historial sea inmutable. Misma decision que
	 * {@code TurnoEventoRepositoryPort} y que {@code platform.spi.audit.AuditTrail}.
	 */
	// =================================================================================
	// Productos, pases y ledger de creditos (AKINE-08.06 / 08.07, M29)
	// =================================================================================

	public interface ProductoServicioRepositoryPort {

		ProductoServicio saveAndFlush(ProductoServicio producto);

		Optional<ProductoServicio> findByIdInScope(
				long organizationId, long consultorioId, long productoId);

		/** Los productos de la sede, vigentes primero. Con {@code incluirBajas} tambien los dados de baja. */
		List<ProductoServicio> findDeLaSede(long organizationId, long consultorioId, boolean incluirBajas);

		/**
		 * {@code true} si ya hay otro producto VIGENTE con ese nombre en la sede. Da el 409
		 * explicable; {@code uk_producto_sede_nombre} cubre la carrera.
		 */
		boolean existeVigenteConNombre(
				long organizationId, long consultorioId, String nombre, long excluirProductoId);

		/**
		 * El producto bloqueado ({@code FOR UPDATE}). Serializa las ventas de abonos del mismo
		 * producto, que es lo que hace cumplir "un periodo, una obligacion": dos ventas simultaneas
		 * de periodos superpuestos no comparten un valor de columna que un unique pueda ver.
		 */
		Optional<ProductoServicio> lockByIdInScope(long organizationId, long consultorioId, long productoId);
	}

	public interface PaseServicioRepositoryPort {

		PaseServicio saveAndFlush(PaseServicio pase);

		Optional<PaseServicio> findByIdInScope(long organizationId, long consultorioId, long paseId);

		Optional<PaseServicio> findByIdempotencyKey(long organizationId, String idempotencyKey);

		List<PaseServicio> findDePersona(long organizationId, long personaId);

		/**
		 * Los pases de la persona que podrian cubrir una asistencia a esa oferta ese dia, en el orden
		 * en que se consumen —el que vence antes primero— y <b>bloqueados</b> ({@code FOR UPDATE}):
		 * es una lectura actual, no la foto de {@code REPEATABLE READ}, y serializa dos consumos
		 * simultaneos de la misma persona.
		 *
		 * <p>Trae el saldo de cada uno leido bajo ese mismo lock (AKINE-09.05): mientras la
		 * transaccion lo retiene nadie mas mueve {@code creditos_disponibles}, asi que despues de un
		 * {@link #consumirUno} que toco la fila el saldo es exactamente uno menos, sin releerlo.
		 */
		List<PaseElegible> lockElegibles(long organizationId, long personaId, long ofertaId, LocalDate fecha);

		/**
		 * Descuenta un credito si y solo si el pase sigue activo, vigente ese dia y con saldo. Una
		 * fila = consumido; cero = no habia. Pasa a {@code AGOTADO} al tomar el ultimo y sube
		 * {@code version}.
		 */
		int consumirUno(long organizationId, long paseId, LocalDate fecha);

		/**
		 * Devuelve un credito si no supera los iniciales. Un {@code AGOTADO} o un {@code VENCIDO}
		 * vuelven a {@code ACTIVO}: el vencido lo vuelve a vencer el job, con su movimiento, y asi el
		 * credito devuelto queda explicado en el ledger. Un {@code ANULADO} no recibe nada.
		 */
		int devolverUno(long organizationId, long paseId);

		/** El saldo actual leido del motor, para el {@code saldo_resultante} del ledger. */
		int saldoActual(long organizationId, long paseId);

		/**
		 * El pase bloqueado ({@code FOR UPDATE}): lectura actual, para los comandos que comparan la
		 * version que el cliente leyo (ajuste y anulacion). Tiene que ser la primera carga del pase
		 * en la transaccion.
		 */
		Optional<PaseServicio> lockByIdInScope(long organizationId, long consultorioId, long paseId);

		/**
		 * Suma {@code delta} al saldo si la version es la leida, el pase esta {@code ACTIVO} o
		 * {@code AGOTADO} y el saldo final queda entre 0 y los creditos iniciales. Deriva
		 * {@code ACTIVO}/{@code AGOTADO} y sube la version.
		 */
		int ajustar(long organizationId, long paseId, int delta, long versionLeida);

		/** {@code ANULADO} con saldo cero, instante, actor y motivo, si la version es la leida. */
		int anular(long organizationId, long paseId, long versionLeida, long actorCuentaId,
				   String motivo, Instant ahora);

		/**
		 * {@code VENCIDO} con saldo cero si sigue {@code ACTIVO} y su vigencia termino antes de
		 * {@code hoy}. La condicion la evalua el motor: dos instancias del job no vencen dos veces.
		 */
		int vencer(long organizationId, long paseId, LocalDate hoy);

		/** Saldo, estado y version leidos del motor despues de un UPDATE nativo. */
		EstadoDePase estadoActual(long organizationId, long paseId);

		/**
		 * Pases {@code ACTIVO} de todos los tenants cuya vigencia termino antes de {@code limite},
		 * los mas viejos primero. Lo lee el job de vencimiento, sin contexto de organizacion.
		 */
		List<CandidatoAVencer> candidatosAVencer(LocalDate limite, int maximo);

		/**
		 * {@code true} si la persona ya tiene un abono vivo (no anulado) de ese producto cuyo
		 * periodo se superpone con el pedido. Un periodo genera como maximo una obligacion.
		 */
		boolean existeAbonoSuperpuesto(
				long organizationId, long personaId, long productoId, LocalDate desde, LocalDate hasta);

		// ---- Renovacion de abonos (AKINE-08.08) -------------------------------------------

		/**
		 * Prende o apaga la renovacion automatica si la version es la leida, el pase es un abono y no
		 * esta anulado. Prenderla fija quien la autorizo; las dos limpian el motivo de una omision
		 * anterior. Sube la version.
		 */
		int configurarRenovacion(
				long organizationId, long paseId, boolean activa, long actorCuentaId, long versionLeida);

		/**
		 * Apaga la renovacion del periodo que se acaba de renovar (el nuevo la hereda). Solo si seguia
		 * prendida: es lo que hace que una segunda pasada del job no encuentre nada. Sube la version.
		 */
		int marcarRenovado(long organizationId, long paseId);

		/** Apaga la renovacion que no se pudo hacer y deja el motivo. Sube la version. */
		int omitirRenovacion(long organizationId, long paseId, String motivo);

		/** El periodo que renueva a este, si existe ({@code uk_pase_servicio_renovacion}). */
		Optional<Long> renovacionDe(long organizationId, long paseId);

		/**
		 * {@link #renovacionDe} para varios pases en UNA consulta: periodo renovado -> periodo que lo
		 * renueva. Los que no se renovaron no aparecen. Para listas: {@link #renovacionDe} por fila
		 * es N+1.
		 */
		java.util.Map<Long, Long> renovacionesDe(long organizationId, java.util.Collection<Long> paseIds);

		/**
		 * Abonos de todos los tenants con la renovacion prendida y el periodo terminando antes de
		 * {@code limite} (inclusive), los que terminan antes primero. Lo lee el job, sin contexto.
		 */
		List<CandidatoARenovar> candidatosARenovar(LocalDate limite, int maximo);

		// ---- Avisos (RF-M26-008, AKINE-08.08) ---------------------------------------------

		/**
		 * Marca resuelto el aviso de vencimiento si todavia no lo estaba y el pase sigue
		 * {@code ACTIVO}. Una fila = este llamador avisa; cero = ya se aviso o ya no corresponde.
		 * Sube la version.
		 */
		int marcarAvisoDeVencimiento(long organizationId, long paseId, Instant ahora);

		/** Igual, para el saldo reducido: exige saldo positivo y no mayor que {@code umbral}. */
		int marcarAvisoDeSaldoBajo(long organizationId, long paseId, int umbral, Instant ahora);

		/**
		 * Pases {@code ACTIVO} de todos los tenants, con saldo, sin aviso de vencimiento y cuya
		 * vigencia termina entre {@code desde} y {@code hasta} (inclusive).
		 */
		List<CandidatoAAvisar> candidatosAvisoDeVencimiento(LocalDate desde, LocalDate hasta, int maximo);

		/** Pases {@code ACTIVO} de todos los tenants con saldo entre 1 y {@code umbral}, sin aviso. */
		List<CandidatoAAvisar> candidatosAvisoDeSaldoBajo(int umbral, int maximo);
	}

	/** Saldo, estado y version del pase leidos del motor. */
	public record EstadoDePase(int saldo, String estado, long version) {
	}

	/** Un pase que el job de vencimiento tiene que mirar. */
	public record CandidatoAVencer(long paseId, long organizationId, long consultorioId,
								   LocalDate vigenciaHasta) {
	}

	/** Un abono que el job de renovacion tiene que mirar. */
	public record CandidatoARenovar(long paseId, long organizationId, long consultorioId, long productoId,
									LocalDate vigenciaHasta) {
	}

	/** Un pase al que el job de avisos tiene que mirar. */
	public record CandidatoAAvisar(long paseId, long organizationId, long consultorioId,
								   LocalDate vigenciaHasta) {
	}

	public interface MovimientoPaseRepositoryPort {

		MovimientoPase registrar(MovimientoPase movimiento);

		List<MovimientoPase> historial(long organizationId, long paseId);

		/**
		 * Una pagina del ledger, del movimiento mas viejo al mas nuevo (AKINE-08.08, RF-M29-009):
		 * el orden en que se lee {@code saldoResultante}. El desempate por id hace estable el corte
		 * entre paginas aunque dos movimientos compartan instante.
		 */
		Page<MovimientoPase> historial(long organizationId, long paseId, Pageable pagina);

		/**
		 * El credito neto que tiene tomado una asistencia, por pase: {@code SUM(cantidad)} de sus
		 * movimientos. Solo filas con neto distinto de cero. Vale -1 o nada.
		 */
		List<NetoDeAsistencia> netoDeAsistencia(long organizationId, long asistenciaId);

		/** Suma del ledger del pase: tiene que coincidir con {@code creditos_disponibles}. */
		int sumaDelLedger(long organizationId, long paseId);

		/**
		 * Los creditos que el pase tiene usados por asistencias: {@code SUM(cantidad)} de sus
		 * {@code CONSUMO} y {@code DEVOLUCION}. Cero si nunca se uso o todo se devolvio.
		 */
		int consumoNeto(long organizationId, long paseId);

		/**
		 * {@link #consumoNeto} de varios pases en una consulta: solo los pases con neto distinto de
		 * cero (el que falta, vale cero).
		 */
		Map<Long, Integer> consumoNetoPorPase(long organizationId, Collection<Long> paseIds);

		/** El ajuste manual aplicado sobre esa version del pase, si existe (reintento). */
		Optional<MovimientoPase> ajusteSobreVersion(long organizationId, long paseId, long versionLeida);

		/** Lo que tiene tomado cada asistencia de una clase: solo las de neto distinto de cero. */
		List<ConsumoDeClase> consumosDeLaClase(long organizationId, long claseId);
	}

	/** El credito que una asistencia de una clase tiene tomado de un pase. */
	public record ConsumoDeClase(long asistenciaId, long paseId, long neto) {
	}

	/** Lo que una asistencia tiene tomado de un pase. */
	public record NetoDeAsistencia(long paseId, long neto) {
	}

	/** Un pase que puede cubrir una asistencia, con el saldo leido bajo su lock. */
	public record PaseElegible(long paseId, int creditosDisponibles) {
	}

	public interface ClaseEventoRepositoryPort {

		ClaseEvento registrar(ClaseEvento evento);

		/** Los eventos de una clase, del mas viejo al mas nuevo. */
		List<ClaseEvento> historial(long organizationId, long claseId);
	}
}
