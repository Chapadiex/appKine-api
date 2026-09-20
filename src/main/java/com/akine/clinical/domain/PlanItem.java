package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una practica planificada dentro de una <b>version</b> de Plan de Tratamiento (RF-M11-002).
 *
 * <h2>Lo que esta clase NO tiene, y que es el punto entero de la etapa</h2>
 *
 * <p><b>No tiene {@code cantidadRealizada} ni {@code cantidadCancelada}</b>, y no porque guardarlas
 * seria peligroso: es que <b>la columna no existe en el esquema</b> (RN-M11-001). Si existiera, su
 * dueño real seria {@code encounter} —el unico modulo que sabe cuando una sesion se cerro— y
 * tendriamos una columna de {@code clinical} que solo otro modulo puede mantener correcta. Ese es
 * el camino por el que un contador se desincroniza: no por mala fe, sino porque el dueño del dato y
 * el dueño de la fila son distintos (challenge seccion 1).
 *
 * <p>El avance se <b>deriva al leer</b>, contando sesiones cerradas del Caso por oferta a traves de
 * {@code clinical.spi.RealizadoEnElCasoProbe}. La ventaja concreta: una sesion que se esta cerrando
 * mientras alguien mira el avance no produce lectura sucia, porque no hay contador que actualizar.
 *
 * <p>Ningun DTO de entrada acepta esos numeros tampoco. La unica forma de cumplir "sin aceptar
 * contadores realizados desde el frontend" que no dependa de la disciplina de quien escriba el
 * proximo endpoint es no tener donde guardarlo.
 *
 * <h2>Cuelga de la version, no del plan</h2>
 *
 * <p>Modificar un plan activo crea una version nueva <b>con su propio juego de items</b>; los de la
 * version anterior quedan intactos. Si colgaran del plan, cambiar una cantidad reescribiria el
 * avance de hace dos meses contra un plan que entonces no existia (RN-M11-003).
 *
 * <h2>El snapshot de la oferta, y por que no alcanza el id</h2>
 *
 * <p>{@link #ofertaNombre} y {@link #servicioId} se congelan al planificar, igual que la obligacion
 * de 07.01 congela el importe. Un plan de seis meses sobrevive al renombre de una oferta y a su
 * baja; sin snapshot, el plan de marzo se lee en septiembre con los nombres de septiembre y nadie
 * entiende que se planifico.
 *
 * <p>Que la oferta se de de baja <b>despues</b> no invalida el plan: es la regla que 02.06 dejo
 * fijada — la baja de un servicio no cascadea, solo impide crear nuevos.
 *
 * <h2>La cantidad autorizada se declara</h2>
 *
 * <p>Hoy siempre con origen {@link OrigenCantidadAutorizada#DECLARADA}. La costura real con las
 * autorizaciones de M17 es <b>04.05</b>, y meter medio consumo aca dejaria dos caminos que despues
 * hay que unificar.
 *
 * <p>Y queda declarado para 04.05, que lo va a necesitar antes de empezar: <b>el avance se cuenta
 * por OFERTA, no por practica individual</b> —los tratamientos realizados dentro de la sesion son
 * 06.04—, asi que comparar "autorizadas" contra "realizadas" va a estar comparando dos
 * granularidades distintas.
 */
@Entity
@Table(name = "plan_item")
public class PlanItem extends MarcaTemporal {

	/** Largo del nombre comercial congelado, tal como lo declara {@code V49}. */
	public static final int NOMBRE_MAXIMO = 160;

	/** Tope de sesiones que el CHECK de {@code V49} admite para cada cantidad. */
	public static final int CANTIDAD_MAXIMA = 9999;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Tenant propietario. Derivable de la version y guardado igual, por el mismo motivo. */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** <b>Version</b> de la que cuelga, no plan. Ver el javadoc de la clase. */
	@Column(name = "plan_tratamiento_version_id", nullable = false, updatable = false)
	private Long planTratamientoVersionId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	/** Sede de la <b>oferta</b>, no del plan: sin ella {@link #ofertaId} no se vuelve a resolver. */
	@Column(name = "oferta_consultorio_id", nullable = false, updatable = false)
	private Long ofertaConsultorioId;

	@Column(name = "oferta_nombre", nullable = false, updatable = false, length = NOMBRE_MAXIMO)
	private String ofertaNombre;

	@Column(name = "servicio_id", nullable = false, updatable = false)
	private Long servicioId;

	/** Lo que la <b>decision clinica</b> estima. Es el dato de la etapa. */
	@Column(name = "cantidad_planificada", nullable = false, updatable = false)
	private Integer cantidadPlanificada;

	/**
	 * Lo que la cobertura otorgo. {@code null} = sin tope declarado, que no es cero.
	 *
	 * <p><b>Editable desde 04.05 y solo por {@link #vincularAutorizacion}</b>, que es lo unico que
	 * puede cambiarla sin crear una version: atar la autorizacion real reemplaza el numero
	 * declarado por el que el financiador otorgo, y son el mismo dato con mejor fuente.
	 */
	@Column(name = "cantidad_autorizada")
	private Integer cantidadAutorizada;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen_autorizacion", nullable = false, length = 16)
	private OrigenCantidadAutorizada origenAutorizacion;

	@Column(name = "autorizacion_id")
	private Long autorizacionId;

	protected PlanItem() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PlanItem(
			Long organizationId,
			Long planTratamientoVersionId,
			Long ofertaId,
			Long ofertaConsultorioId,
			String ofertaNombre,
			Long servicioId,
			int cantidadPlanificada,
			Integer cantidadAutorizada) {

		this.organizationId = exigirNoNulo(organizationId, "El item pertenece a una organizacion");
		this.planTratamientoVersionId = exigirNoNulo(
				planTratamientoVersionId, "El item cuelga siempre de una version del plan");
		this.ofertaId = exigirNoNulo(ofertaId, "El item declara siempre la oferta planificada");
		this.ofertaConsultorioId = exigirNoNulo(
				ofertaConsultorioId, "La oferta se resuelve siempre dentro de una sede");
		String nombre = vacioEsNulo(ofertaNombre);
		if (nombre == null) {
			throw new IllegalArgumentException("El item congela el nombre de la oferta");
		}
		this.ofertaNombre = nombre.length() > NOMBRE_MAXIMO
				? nombre.substring(0, NOMBRE_MAXIMO)
				: nombre;
		this.servicioId = exigirNoNulo(servicioId, "El item congela el servicio de la oferta");

		if (cantidadPlanificada <= 0 || cantidadPlanificada > CANTIDAD_MAXIMA) {
			throw new IllegalArgumentException(
					"La cantidad planificada tiene que estar entre 1 y " + CANTIDAD_MAXIMA);
		}
		this.cantidadPlanificada = cantidadPlanificada;

		if (cantidadAutorizada != null
				&& (cantidadAutorizada < 0 || cantidadAutorizada > CANTIDAD_MAXIMA)) {
			throw new IllegalArgumentException(
					"La cantidad autorizada tiene que estar entre 0 y " + CANTIDAD_MAXIMA);
		}
		this.cantidadAutorizada = cantidadAutorizada;

		// DECLARADA sin referencia es el unico par que esta etapa produce, y el CHECK
		// ck_plan_item_origen_trazable lo verifica del lado del motor.
		this.origenAutorizacion = OrigenCantidadAutorizada.DECLARADA;
		this.autorizacionId = null;
	}

	/**
	 * Ata este item a una {@code Autorizacion} real de M17 (RF-M11-007).
	 *
	 * <p>Es lo que convierte la cantidad <b>declarada</b> de 04.04 —la que escribio a mano quien
	 * planifico, mirando el papel del financiador— en una referencia verificable. El origen pasa a
	 * {@link OrigenCantidadAutorizada#AUTORIZACION}, que es el valor que 04.04 dejo declarado en el
	 * enum y que hasta ahora nadie escribia.
	 *
	 * <h2>Esto NO crea una version del plan, y es deliberado</h2>
	 *
	 * <p>04.04 fijo que modificar un plan activo escribe una version nueva, porque cambiar lo
	 * <b>planificado</b> reescribiria el avance de hace dos meses contra un plan que entonces no
	 * existia (RN-M11-003). Atar la autorizacion no cambia nada de lo planificado: cambia la
	 * FUENTE del mismo numero. Versionarlo llenaria el historial clinico de versiones cuya unica
	 * diferencia es administrativa, y dejaria las versiones anteriores del plan apuntando a una
	 * autorizacion que tambien es suya.
	 *
	 * <p>Lo que si queda asentado es un {@code plan_evento}: el hecho ocurrio y el historial lo
	 * dice, sin duplicar el contenido.
	 *
	 * <p>{@code cantidadAutorizada} pasa a ser la que el financiador otorgo. Puede ser <b>menor</b>
	 * que la declarada —eso es la autorizacion parcial— y puede quedar por debajo de
	 * {@link #cantidadPlanificada}: es informacion clinica valiosa, no un error. Planificar mas de
	 * lo que la cobertura cubre es una situacion real que el centro resuelve cobrandole al
	 * paciente la diferencia, y rechazarla aca le impediria al profesional registrar lo que
	 * corresponde clinicamente.
	 *
	 * <p><b>No comprueba que la autorizacion exista, sirva o sea del paciente.</b> Eso no lo puede
	 * decidir una instancia de {@code clinical}: la autorizacion vive en {@code person} y se
	 * consulta por su {@code spi}. Esta clase asume que ya se verifico, igual que el resto del
	 * dominio del modulo.
	 */
	public void vincularAutorizacion(long autorizacionId, Integer cantidadAutorizada) {
		if (cantidadAutorizada != null
				&& (cantidadAutorizada < 0 || cantidadAutorizada > CANTIDAD_MAXIMA)) {
			throw new IllegalArgumentException(
					"La cantidad autorizada tiene que estar entre 0 y " + CANTIDAD_MAXIMA);
		}
		this.autorizacionId = autorizacionId;
		this.origenAutorizacion = OrigenCantidadAutorizada.AUTORIZACION;
		this.cantidadAutorizada = cantidadAutorizada;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPlanTratamientoVersionId() {
		return planTratamientoVersionId;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public Long getOfertaConsultorioId() {
		return ofertaConsultorioId;
	}

	public String getOfertaNombre() {
		return ofertaNombre;
	}

	public Long getServicioId() {
		return servicioId;
	}

	public Integer getCantidadPlanificada() {
		return cantidadPlanificada;
	}

	public Integer getCantidadAutorizada() {
		return cantidadAutorizada;
	}

	public OrigenCantidadAutorizada getOrigenAutorizacion() {
		return origenAutorizacion;
	}

	public Long getAutorizacionId() {
		return autorizacionId;
	}
}
