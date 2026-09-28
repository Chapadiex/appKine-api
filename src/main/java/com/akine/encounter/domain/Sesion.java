package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionNoCerradaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Una atencion real. <b>No es el turno.</b>
 *
 * <p>DP-05: Turno, Recepcion y Sesion son tres maquinas de estado independientes. El Turno dice que
 * un lugar quedo tomado; esta clase dice que hubo atencion. Por eso {@code turnoId} es opcional
 * —RF-M14-002 admite atencion sin turno— y la sesion se guarda su propio profesional y su propia
 * oferta en vez de leerlos del turno cada vez.
 *
 * <h2>De donde cuelga, y por que no de un Caso</h2>
 *
 * <p>El plan dice "una sesion pertenece a un Caso", y el Caso Clinico (04.03) quedo fuera del
 * Paquete B por DP-10. Cuelga entonces de la <b>Historia Clinica</b>, que existe desde 04.01, es de
 * la organizacion (DP-03) y es el contexto longitudinal del paciente. Cuando 04.03 llegue, agrega
 * un {@code casoId} nullable y estas sesiones siguen siendo legibles.
 *
 * <h2>El borrador es opaco</h2>
 *
 * <p>Que campos tiene una evaluacion es asunto de 06.02 y 06.03, y 06.03 quedo cortada. Esta clase
 * guarda el borrador, lo versiona y garantiza que no se pierda; no sabe ni valida su forma. Darle
 * esquema hoy seria fijar en la base un formulario que todavia no esta decidido.
 */
@Entity
@Table(name = "sesion")
public class Sesion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	/**
	 * Caso Clinico al que pertenece la atencion (04.03). {@code null} es legitimo.
	 *
	 * <p>RF-M14-002 admite atencion sin turno y sin caso, y <b>todas</b> las sesiones anteriores a
	 * 04.03 no tienen caso: el caso no existia cuando se cerraron y no hay dato que permita
	 * inventarles uno. Exigirlo es RF-M10-007, que es una etapa propia con su ventana de migracion.
	 *
	 * <p>Es un {@code Long} y no una relacion JPA: {@code caso_clinico} es de {@code clinical} y una
	 * relacion hacia su entity seria justamente el acceso directo que AGENT.md seccion 4 regla 1
	 * prohibe. Lo que este modulo puede hacer con un caso lo dice {@code clinical.spi.CasoDirectory}.
	 */
	@Column(name = "caso_id", updatable = false)
	private Long casoId;

	@Column(name = "turno_id", updatable = false)
	private Long turnoId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "profesional_membership_id", nullable = false, updatable = false)
	private Long profesionalMembershipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoSesion estado;

	@Column(name = "iniciada_en", nullable = false, updatable = false)
	private Instant iniciadaEn;

	@Column(name = "iniciada_por_cuenta_id", nullable = false, updatable = false)
	private Long iniciadaPorCuentaId;

	@Column(name = "borrador", columnDefinition = "json")
	private String borrador;

	@Column(name = "borrador_guardado_en")
	private Instant borradorGuardadoEn;

	@Enumerated(EnumType.STRING)
	@Column(name = "modo", length = 16)
	private ModoSesion modo;

	@Column(name = "motivo_clinico", length = 500)
	private String motivoClinico;

	@Column(name = "dolor_eva")
	private Integer dolorEva;

	@Column(name = "dolor_zona", length = 120)
	private String dolorZona;

	@Enumerated(EnumType.STRING)
	@Column(name = "dolor_lateralidad", length = 16)
	private Lateralidad dolorLateralidad;

	@Enumerated(EnumType.STRING)
	@Column(name = "evolucion", length = 16)
	private Evolucion evolucion;

	@Column(name = "objetivo_sesion", length = 500)
	private String objetivoSesion;

	@Column(name = "limitacion_funcional", length = 500)
	private String limitacionFuncional;

	@Column(name = "evaluada_en")
	private Instant evaluadaEn;

	@Column(name = "numero_sesion")
	private Integer numeroSesion;

	/**
	 * Correlativo de la sesion <b>dentro del caso</b> (regla maestra 3, 04.03).
	 *
	 * <p>Convive con {@link #numeroSesion}, que es el correlativo por Historia Clinica que 06.05
	 * asigna y que <b>no se renumera ni se retira</b>: esta impreso en informes y es el unico que
	 * existe para una sesion sin caso.
	 *
	 * <p><b>Consecuencia que hay que saber leer:</b> "la sesion 8" es ambigua si no se dice de que.
	 * Los DTO devuelven los dos con nombres distintos y ninguna pantalla puede mostrar uno solo sin
	 * decir cual es.
	 *
	 * <p>El numero no se calcula aca: se lo pide a {@code clinical} por el spi, dentro de la
	 * transaccion del cierre. El {@code CHECK} de V48 impide que exista sin {@link #casoId}.
	 */
	@Column(name = "numero_en_caso")
	private Integer numeroEnCaso;

	@Column(name = "respuesta_tratamiento", length = 500)
	private String respuestaTratamiento;

	@Enumerated(EnumType.STRING)
	@Column(name = "tolerancia", length = 16)
	private Tolerancia tolerancia;

	@Column(name = "indicaciones", length = 1000)
	private String indicaciones;

	@Enumerated(EnumType.STRING)
	@Column(name = "proxima_conducta", length = 16)
	private ProximaConducta proximaConducta;

	@Column(name = "nota_de_cierre", length = 2000)
	private String notaDeCierre;

	@Enumerated(EnumType.STRING)
	@Column(name = "asistencia", length = 16)
	private Asistencia asistencia;

	@Column(name = "cerrada_en")
	private Instant cerradaEn;

	@Column(name = "cerrada_por_cuenta_id")
	private Long cerradaPorCuentaId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/**
	 * Numero de la ultima version de contenido escrita (06.06). <b>Es el numerador de enmiendas.</b>
	 *
	 * <p>Vale {@code 0} mientras la sesion esta abierta —no hay contenido versionado hasta el
	 * cierre— y {@code 1} desde que se cierra. Cada enmienda lo sube en uno.
	 *
	 * <p><b>No hay ningun {@code MAX(numero_version) + 1}</b>: dos enmiendas concurrentes
	 * calculando ese MAX devuelven el mismo numero y dejan dos "version 3" sin criterio para
	 * desempatarlas. El numero sale de este contador, que vive en la fila que la enmienda va a
	 * ensuciar de todos modos.
	 *
	 * <p>Y por eso la enmienda <b>no</b> usa {@code OPTIMISTIC_FORCE_INCREMENT}: mover este
	 * contador ensucia la fila, asi que el flush ya emite un {@code UPDATE ... WHERE version = N}
	 * versionado y esa es toda la garantia. Forzar el incremento encima dejaria la base en
	 * {@code leida + 2} devolviendo {@code leida + 1}, y el cliente comeria un 409 del que no puede
	 * salir. La regla que queda: <b>force-increment solo donde la escritura NO toca ninguna columna
	 * del padre</b> — 04.02 la pago y {@code offering.OfertaHabilitacionService} es hoy su unica
	 * ocurrencia legitima.
	 */
	@Column(name = "ultimo_numero_version", nullable = false)
	private int ultimoNumeroVersion;

	/**
	 * El control optimista <b>es</b> el autosave, no un adorno.
	 *
	 * <p>Dos pestanas del mismo profesional sobre la misma sesion son el caso normal. Sin esto la
	 * segunda pisa a la primera en silencio y el profesional pierde lo que escribio sin enterarse.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Sesion() {
		// Requerido por JPA.
	}

	public Sesion(
			long organizationId,
			long consultorioId,
			long historiaClinicaId,
			Long casoId,
			Long turnoId,
			long ofertaId,
			long profesionalMembershipId,
			Instant iniciadaEn,
			long iniciadaPorCuentaId) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.historiaClinicaId = historiaClinicaId;
		this.casoId = casoId;
		this.turnoId = turnoId;
		this.ofertaId = ofertaId;
		this.profesionalMembershipId = profesionalMembershipId;
		this.estado = EstadoSesion.BORRADOR;
		this.iniciadaEn = iniciadaEn;
		this.iniciadaPorCuentaId = iniciadaPorCuentaId;
	}

	/**
	 * Guarda el borrador.
	 *
	 * <p>El contenido no se valida: ver la cabecera. Lo que si se hace cumplir es que la sesion
	 * siga abierta — una sesion cerrada no se edita, se enmienda, y la enmienda es 06.06, que el
	 * Paquete B dejo afuera.
	 */
	public void guardarBorrador(String contenido, Instant occurredAt) {
		exigirAbierta();
		this.borrador = contenido;
		this.borradorGuardadoEn = occurredAt;
	}

	/**
	 * Exige que quien opera sea el profesional de la sesion.
	 *
	 * <p>RN-M14-003 y la instruccion de la etapa: "bloqueo de edicion ajena". <b>El permiso no
	 * alcanza</b>: dos profesionales de la misma sede tienen el mismo {@code sesion:register}, y sin
	 * este control cualquiera de los dos escribe en la atencion del otro. Es una regla de propiedad,
	 * no de autorizacion, y por eso vive en la entidad y no en el evaluador de permisos.
	 *
	 * <p>El reemplazo autorizado que menciona la etapa —otro profesional que continua una atencion—
	 * necesita un registro propio de quien reemplaza a quien y por que. No existe todavia; hasta que
	 * exista, esto es fail-closed.
	 */
	public void exigirPropiedadDe(long membershipId) {
		if (!profesionalMembershipId.equals(membershipId)) {
			throw new SesionAjenaException(id, profesionalMembershipId);
		}
	}

	/**
	 * Guarda la evaluacion base.
	 *
	 * <p><b>Ningun campo es obligatorio, y es una regla de negocio.</b> "Seguimiento no exige examen
	 * completo": una sesion de seguimiento carga dolor y evolucion y nada mas. Exigir cualquiera de
	 * estos campos obligaria a inventar datos clinicos para poder guardar.
	 *
	 * <p>Lo unico que se valida es lo que seria FALSO, no lo que falta: el dolor fuera de la escala
	 * y la lateralidad sin zona. El primero es un dato que despues alguien promedia; el segundo no
	 * significa nada —"derecha" de que—.
	 */
	public void evaluar(EvaluacionBase evaluacion, Instant occurredAt) {
		exigirAbierta();
		evaluacion.exigirCoherente();

		this.modo = evaluacion.modo();
		this.motivoClinico = evaluacion.motivoClinico();
		this.dolorEva = evaluacion.dolorEva();
		this.dolorZona = evaluacion.dolorZona();
		this.dolorLateralidad = evaluacion.dolorLateralidad();
		this.evolucion = evaluacion.evolucion();
		this.objetivoSesion = evaluacion.objetivoSesion();
		this.limitacionFuncional = evaluacion.limitacionFuncional();
		this.evaluadaEn = occurredAt;
	}

	/**
	 * Cierra la atencion con el numero que le toca.
	 *
	 * <p><b>Es idempotente y esa es la mitad del requisito.</b> RN-M14-005 pide un comando con
	 * resultado estable ante retry, y un profesional que aprieta dos veces "cerrar" es el caso
	 * normal. Una sesion ya cerrada se devuelve tal cual: <b>no se renumera</b>, porque renumerar
	 * una sesion cerrada es reescribir historia clinica, y no se sobreescriben sus datos, porque
	 * corregir una sesion cerrada es una enmienda —06.06— y no un segundo cierre.
	 *
	 * <p>El llamador tiene que consultar {@link #estaCerrada()} ANTES de pedir un numero al
	 * numerador: si no, cada reintento consume un correlativo que despues nadie usa, y la
	 * numeracion del paciente queda con huecos que parecen sesiones borradas.
	 *
	 * @param numero      correlativo por Historia Clinica. Siempre presente
	 * @param numeroEnCaso correlativo dentro del Caso (04.03). {@code null} cuando la sesion no
	 *                     tiene caso, que es lo que son todas las anteriores a esa etapa. Se
	 *                     ignora si viene sin {@link #casoId}: el CHECK de V48 lo rechazaria, y
	 *                     fallar aca con un mensaje que nombra la situacion es mejor que fallar al
	 *                     commitear con uno que no
	 */
	public void cerrar(
			CierreDeSesion cierre,
			int numero,
			Integer numeroEnCaso,
			Instant occurredAt,
			long cerradaPorCuentaId) {

		if (estaCerrada()) {
			return;
		}
		if (numeroEnCaso != null && casoId == null) {
			throw new IllegalArgumentException(
					"Una sesion sin caso no puede recibir un numero de caso: el correlativo "
							+ "numeraria dentro de nada");
		}
		cierre.exigirMinimos();

		this.asistencia = cierre.asistencia();
		this.notaDeCierre = cierre.notaDeCierre();
		this.respuestaTratamiento = cierre.respuestaTratamiento();
		this.tolerancia = cierre.tolerancia();
		this.indicaciones = cierre.indicaciones();
		this.proximaConducta = cierre.proximaConducta();

		this.numeroSesion = numero;
		this.numeroEnCaso = numeroEnCaso;
		this.estado = EstadoSesion.CERRADA;
		this.cerradaEn = occurredAt;
		this.cerradaPorCuentaId = cerradaPorCuentaId;

		// 06.06: el cierre inaugura el historial de contenido. La version 1 es lo que se acaba de
		// asentar, y el llamador la persiste en la MISMA transaccion. Escribirla aca y no
		// perezosamente en la primera enmienda es lo que hace que toda sesion cerrada tenga
		// historial completo y que consultarlo sea una sola consulta — ver SesionVersion.
		this.ultimoNumeroVersion = 1;
	}

	/**
	 * Aplica una enmienda al contenido clinico y reserva el numero de la version nueva
	 * (RF-M14-010).
	 *
	 * <h2>Enmendar no es un UPDATE, aunque esta fila se actualice</h2>
	 *
	 * <p>Lo que hace este metodo es mover el contenido <b>vigente</b>. El original no se pierde:
	 * vive en la {@code SesionVersion} numero 1, que el cierre ya escribio, y la version que este
	 * metodo numera se escribe al lado. RN-M14-006 no prohibe corregir una sesion cerrada: prohibe
	 * corregirla <b>silenciosamente</b>.
	 *
	 * <h2>Las dos validaciones se REUSAN, y no es pereza</h2>
	 *
	 * <p>La coherencia de la evaluacion y los minimos del cierre salen de {@link EvaluacionBase} y
	 * {@link CierreDeSesion}, los mismos tipos que valida el camino normal. Una enmienda no puede
	 * guardar un EVA de 12 que la evaluacion original habria rechazado, ni <b>vaciar la nota de
	 * cierre de una sesion con el paciente presente</b> —eso dejaria una prestacion que ocurrio sin
	 * nada que diga que se hizo—. Escribir validaciones propias aca garantizaria que en algun
	 * momento las dos listas divergen.
	 *
	 * <p>La asistencia y el modo los pone <b>esta sesion</b>, no el pedido: no son enmendables.
	 * Ver {@link ContenidoDeSesion}.
	 *
	 * <h2>Lo que deliberadamente NO cambia</h2>
	 *
	 * <p>Ni {@link #numeroSesion} ni {@link #numeroEnCaso} —renumerar una sesion cerrada es
	 * reescribir historia clinica, y 04.03 lo rechazo explicitamente—, ni {@link #cerradaEn}, ni
	 * {@link #cerradaPorCuentaId}, ni {@link #evaluadaEn}. Los tres ultimos dicen <b>cuando paso</b>
	 * y quien lo asento; el instante y el autor de la enmienda son de la version, no de la sesion.
	 *
	 * @return el numero de la version que esta enmienda produce
	 * @throws SesionNoCerradaException si la sesion sigue abierta: eso se guarda, no se enmienda
	 */
	public int enmendar(ContenidoDeSesion contenido, Asistencia asistencia) {
		if (!estaCerrada()) {
			throw new SesionNoCerradaException(id);
		}
		EvaluacionBase evaluacion = contenido.evaluacionCon(modo);
		evaluacion.exigirCoherente();

		CierreDeSesion cierre = contenido.cierreCon(asistencia);
		cierre.exigirMinimos();

		this.motivoClinico = evaluacion.motivoClinico();
		this.dolorEva = evaluacion.dolorEva();
		this.dolorZona = evaluacion.dolorZona();
		this.dolorLateralidad = evaluacion.dolorLateralidad();
		this.evolucion = evaluacion.evolucion();
		this.objetivoSesion = evaluacion.objetivoSesion();
		this.limitacionFuncional = evaluacion.limitacionFuncional();

		this.notaDeCierre = cierre.notaDeCierre();
		this.respuestaTratamiento = cierre.respuestaTratamiento();
		this.tolerancia = cierre.tolerancia();
		this.indicaciones = cierre.indicaciones();
		this.proximaConducta = cierre.proximaConducta();

		this.ultimoNumeroVersion += 1;
		return this.ultimoNumeroVersion;
	}

	/** {@code true} si la sesion fue enmendada al menos una vez. */
	public boolean fueEnmendada() {
		return ultimoNumeroVersion > 1;
	}

	/**
	 * {@code true} si la atencion ya se cerro.
	 *
	 * <p>Se decide por {@code numeroSesion} y no por el estado: el CHECK de V35 garantiza que el
	 * numero, el instante y el actor van los tres o ninguno, asi que una fila a medio cerrar no
	 * existe. Preguntar por el numero ademas es lo que impide consumir un correlativo por cada
	 * reintento.
	 */
	public boolean estaCerrada() {
		return numeroSesion != null;
	}

	/**
	 * Una sesion cerrada no se edita.
	 *
	 * <p>Corregir lo que dice una atencion cerrada es una ENMIENDA, con su actor y su motivo, y eso
	 * es 06.06 — fuera del Paquete B. Hasta que exista, esto es fail-closed: es preferible no poder
	 * corregir a corregir sin dejar rastro, porque lo segundo es historia clinica reescrita en
	 * silencio.
	 */
	private void exigirAbierta() {
		if (estaCerrada()) {
			throw new SesionCerradaException(id);
		}
	}

	public boolean estaViva() {
		return deletedAt == null;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getHistoriaClinicaId() {
		return historiaClinicaId;
	}

	/** El Caso al que pertenece la atencion, o {@code null} si no tiene. Ver el campo. */
	public Long getCasoId() {
		return casoId;
	}

	/** El correlativo dentro del caso, o {@code null}. <b>No es {@link #getNumeroSesion()}.</b> */
	public Integer getNumeroEnCaso() {
		return numeroEnCaso;
	}

	public Long getTurnoId() {
		return turnoId;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public EstadoSesion getEstado() {
		return estado;
	}

	public Instant getIniciadaEn() {
		return iniciadaEn;
	}

	public Long getIniciadaPorCuentaId() {
		return iniciadaPorCuentaId;
	}

	public String getBorrador() {
		return borrador;
	}

	public Instant getBorradorGuardadoEn() {
		return borradorGuardadoEn;
	}

	public ModoSesion getModo() {
		return modo;
	}

	public String getMotivoClinico() {
		return motivoClinico;
	}

	public Integer getDolorEva() {
		return dolorEva;
	}

	public String getDolorZona() {
		return dolorZona;
	}

	public Lateralidad getDolorLateralidad() {
		return dolorLateralidad;
	}

	public Evolucion getEvolucion() {
		return evolucion;
	}

	public String getObjetivoSesion() {
		return objetivoSesion;
	}

	public String getLimitacionFuncional() {
		return limitacionFuncional;
	}

	public Instant getEvaluadaEn() {
		return evaluadaEn;
	}

	public Integer getNumeroSesion() {
		return numeroSesion;
	}

	public String getRespuestaTratamiento() {
		return respuestaTratamiento;
	}

	public Tolerancia getTolerancia() {
		return tolerancia;
	}

	public String getIndicaciones() {
		return indicaciones;
	}

	public ProximaConducta getProximaConducta() {
		return proximaConducta;
	}

	public String getNotaDeCierre() {
		return notaDeCierre;
	}

	public Asistencia getAsistencia() {
		return asistencia;
	}

	public Instant getCerradaEn() {
		return cerradaEn;
	}

	public Long getCerradaPorCuentaId() {
		return cerradaPorCuentaId;
	}
	public long getVersion() {
		return version;
	}

	/** Numero de la ultima version de contenido. {@code 0} mientras la sesion esta abierta. */
	public int getUltimoNumeroVersion() {
		return ultimoNumeroVersion;
	}
}
