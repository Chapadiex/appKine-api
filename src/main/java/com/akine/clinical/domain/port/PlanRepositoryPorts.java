package com.akine.clinical.domain.port;

import com.akine.clinical.domain.AutorizacionVinculadaACaso;
import com.akine.clinical.domain.PlanEvento;
import com.akine.clinical.domain.PlanItem;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;

import java.util.List;
import java.util.Optional;

/**
 * Los puertos de persistencia del Plan de Tratamiento, declarados en {@code domain}.
 *
 * <p>Viven aca y no en {@code infrastructure} por la regla que 01.01 dejo fijada: {@code
 * application} consume <b>puertos en {@code domain}</b>, nunca repositorios de
 * {@code infrastructure}. La direccion permitida es {@code infrastructure -> application}, y
 * ArchUnit la verifica.
 *
 * <p>Van en un archivo propio y no dentro de {@code CasoRepositoryPorts} por lo mismo que aquel no
 * fue a dar a {@code ClinicalRepositoryPorts}: un archivo que agrupa todos los puertos del modulo
 * es el primer paso hacia la capa global que AGENT.md seccion 4 regla 3 prohibe.
 *
 * <p><b>Toda firma lleva {@code organizationId}, sin excepcion.</b> No hay ni un metodo que
 * resuelva por id pelado: un {@code findById} en un modulo clinico es una fuga de tenant esperando
 * a que alguien lo llame desde un camino que no filtro antes.
 */
public final class PlanRepositoryPorts {

	private PlanRepositoryPorts() {
	}

	/**
	 * La cabecera del plan.
	 *
	 * <p><b>No declara ningun {@code findWithLock...} y la ausencia es la decision.</b> Toda
	 * escritura de esta etapa toca columnas de {@code plan_tratamiento} —el estado, o el contador de
	 * versiones— asi que el {@code UPDATE ... WHERE version = N} que emite JPA ya serializa a dos
	 * escritores concurrentes. Un {@code OPTIMISTIC_FORCE_INCREMENT} encima haria avanzar la version
	 * <b>dos</b> veces y devolveria {@code leida + 1}: el cliente manda esa version y come un 409 del
	 * que no puede salir. Es el defecto que 04.02 pago y corrigio, y la regla que quedo es
	 * <b>force-increment solo donde la escritura no toca ninguna columna del padre</b>.
	 */
	public interface PlanTratamientoRepositoryPort {

		PlanTratamiento save(PlanTratamiento plan);

		/**
		 * Guarda y fuerza el flush.
		 *
		 * <p>Hace falta en dos lugares distintos. Al crear, porque la version 1 necesita el id de la
		 * cabecera y sin flush ese id no existe hasta el cierre de la transaccion. Al modificar,
		 * porque {@code save} es un merge: deja la escritura pendiente y el {@code UPDATE} que sube
		 * la {@code version} recien sale al commit, <b>despues</b> de que la vista ya leyo
		 * {@code getVersion()}. El cliente se llevaria la version vieja, la mandaria como
		 * {@code expectedVersion} en la operacion siguiente y comeria un 409 del que no puede salir
		 * salvo releyendo. Es la regla del repositorio "save() antes del flush devuelve la version
		 * vieja", ya pagada en 02.07.
		 */
		PlanTratamiento saveAndFlush(PlanTratamiento plan);

		Optional<PlanTratamiento> findByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * Los planes de un caso, mas recientes primero.
		 *
		 * @param soloVigentes {@code true} deja afuera los FINALIZADOS; {@code false} los incluye,
		 *                     que es lo que hace consultable el historico de tratamientos del
		 *                     paciente. <b>Un plan finalizado no es un plan borrado</b> (regla
		 *                     maestra 10)
		 */
		List<PlanTratamiento> buscarDeCaso(
				Long organizationId, Long casoClinicoId, boolean soloVigentes);

		/**
		 * El plan ACTIVO del caso, si hay.
		 *
		 * <p>Devuelve como mucho uno, y no por convencion: {@code uk_plan_activo_por_caso} lo hace
		 * cumplir del lado del motor. Lo consume la activacion, que <b>finaliza el anterior en la
		 * misma transaccion</b> antes de activar el suyo. Si dos activaciones llegan juntas, las dos
		 * pueden leer {@code empty} o el mismo plan: la que pierda choca contra el unique y recibe
		 * 409, que es el desenlace correcto — no hay ventana en la que queden dos vivos.
		 */
		Optional<PlanTratamiento> buscarQueOcupaElLugarDelCaso(Long organizationId, Long casoClinicoId);
	}

	/**
	 * Las versiones de contenido.
	 *
	 * <p><b>No declara {@code delete}</b>: una version es un hecho pasado y darla de baja seria
	 * reescribir historia clinica (ADR-0011). {@code save} se usa para insertar la version nueva y,
	 * solo mientras el plan esta en BORRADOR, para persistir la edicion en el lugar de la version 1.
	 */
	public interface PlanTratamientoVersionRepositoryPort {

		PlanTratamientoVersion save(PlanTratamientoVersion version);

		/**
		 * Guarda y fuerza el flush. Lo necesitan los items, que cuelgan del id de la version.
		 */
		PlanTratamientoVersion saveAndFlush(PlanTratamientoVersion version);

		/** Las versiones de un plan, de la mas nueva a la mas vieja (RF-M11-004). */
		List<PlanTratamientoVersion> buscarDePlan(Long organizationId, Long planTratamientoId);

		/**
		 * La version vigente del plan: la de numero mas alto.
		 *
		 * <p>Es la que la ficha muestra y contra cuyos items se calcula el avance por defecto.
		 */
		Optional<PlanTratamientoVersion> buscarVigente(
				Long organizationId, Long planTratamientoId);

		/** Una version puntual del plan. Es lo que permite leer el avance historico. */
		Optional<PlanTratamientoVersion> buscarPorNumero(
				Long organizationId, Long planTratamientoId, int numeroVersion);
	}

	/**
	 * Las practicas planificadas.
	 *
	 * <p><b>No declara {@code delete} ni ninguna actualizacion de cantidades.</b> Modificar un plan
	 * no borra los items de la version anterior: escribe los de la version nueva. Y no hay nada que
	 * actualizar despues, porque no existen las columnas de realizadas ni canceladas (RN-M11-001).
	 */
	public interface PlanItemRepositoryPort {

		List<PlanItem> saveAll(List<PlanItem> items);

		/** Los items de una version, en el orden en que se planificaron. */
		List<PlanItem> buscarDeVersion(Long organizationId, Long planTratamientoVersionId);

		/**
		 * Vacia los items de la version 1 de un plan que sigue en <b>BORRADOR</b>, para volver a
		 * escribirlos.
		 *
		 * <h2>Es el unico borrado fisico del modulo clinico, y hay que mirarlo de frente</h2>
		 *
		 * <p>La regla maestra 10 prohibe eliminar fisicamente <b>informacion historica
		 * relevante</b>. Los items de un plan que nunca se activo no lo son: son un formulario a
		 * medio llenar. Nadie planifico nada contra ellos, ninguna sesion se conto contra ellos y
		 * ninguna version posterior los referencia — la version 1 de un borrador todavia no es un
		 * hecho pasado, es el hecho presente.
		 *
		 * <p>La alternativa era versionar cada edicion de borrador, y el diseño la rechaza
		 * explicitamente (seccion 4): versionar cada tecleo llenaria la tabla de ruido que nadie va
		 * a leer nunca. La otra alternativa —baja logica de item— agregaria un {@code active} a una
		 * tabla que no lo necesita en ningun otro camino, y una columna que solo tiene sentido en
		 * un estado es una columna que alguien va a olvidarse de filtrar.
		 *
		 * <p><b>Quien garantiza que esto solo se llame sobre un borrador es el servicio</b>, que lo
		 * invoca dentro de la rama {@code esBorrador()} y en ninguna otra parte. En cuanto el plan
		 * pasa a ACTIVO, toda modificacion escribe filas nuevas y las viejas quedan intactas
		 * (RN-M11-003).
		 */
		void borrarDeVersionEnBorrador(Long organizationId, Long planTratamientoVersionId);

		/**
		 * Autorizaciones atadas a items de planes de los casos de una historia clinica, con el caso
		 * de cada plan (AKINE C-4, RF-M17-007).
		 * Todas las versiones y todos los estados del plan: la autorizacion ya se ato a ese caso.
		 */
		List<AutorizacionVinculadaACaso> autorizacionesConCasoDeLaHistoria(
				Long organizationId, Long historiaClinicaId);
	}

	/**
	 * El historial de estados. <b>Append-only</b>: no declara {@code update} ni {@code delete}, y
	 * la ausencia es el contrato — un historial que se puede editar no es un historial.
	 */
	public interface PlanEventoRepositoryPort {

		PlanEvento save(PlanEvento evento);

		/** Los eventos de un plan, del mas viejo al mas nuevo: se lee como una linea de tiempo. */
		List<PlanEvento> buscarDePlan(Long organizationId, Long planTratamientoId);
	}

	/**
	 * Secuencia de planes por Caso Clinico.
	 *
	 * <p><b>Tres operaciones y no una</b>, para que el orden quede a la vista de quien lee el
	 * servicio: asegurar la fila —en su propia transaccion—, incrementar y leer. Esconderlo detras
	 * de un solo {@code siguiente()} haria invisible que el primer paso NO puede ir dentro de la
	 * transaccion que despues la bloquea: esa es la creacion perezosa que produce deadlock y que
	 * este repositorio ya pago cuatro veces. Mismo reparto que {@code CasoNumeradorPort}.
	 */
	public interface PlanNumeradorPort {

		/** Crea la fila si falta. Sin lanzar. Va en una transaccion aparte: ver la implementacion. */
		void crearSiFalta(long organizationId, long casoClinicoId);

		/** Incrementa el contador tomando el lock de fila. Serializa las altas de ese caso. */
		void incrementar(long organizationId, long casoClinicoId);

		/** Lee el numero recien asignado, en la misma transaccion que lo incremento. */
		Integer leerUltimo(long organizationId, long casoClinicoId);
	}
}
