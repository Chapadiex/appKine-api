package com.akine.encounter.domain;

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
 * Una intervencion <b>realmente aplicada</b> en una sesion (RF-M14-005), con el espacio
 * <b>realmente utilizado</b> (RF-M04-005).
 *
 * <h2>RN-M14-004: planificado no equivale a realizado. Esta clase es el lado "realizado"</h2>
 *
 * <p>04.04 registro lo que un profesional <b>decidio</b> hacer y 06.01–06.05 registraron que
 * <b>hubo una atencion</b>. Lo que nunca existio es el dato del medio: que prestaciones se
 * aplicaron en esa atencion. El sistema podia decir "hubo una sesion de Kinesiologia deportiva" y
 * no podia decir si hubo electroterapia, terapia manual o las dos.
 *
 * <p><b>No hay ninguna referencia a {@code plan_item}, y es deliberado.</b> Atar lo realizado a lo
 * planificado haria que registrar una intervencion moviera el plan, que es exactamente la
 * confusion que 04.04 se nego a cometer cuando decidio no tener columna {@code cantidad_realizada}.
 *
 * <h2>Lo que esta clase destraba, y lo que no</h2>
 *
 * <p>Es la <b>unica</b> tabla del sistema que vincula una atencion con una practica del catalogo
 * M06. La autorizacion de M17 se otorga por {@code practica_id} y hasta 04.05 el consumo elegia
 * "la que vence antes" sin mirar la practica, asi que podia gastar la autorizacion equivocada.
 * Con esto deja de poder.
 *
 * <p>Lo que <b>no</b> destraba: el avance del Plan de Tratamiento sigue contandose por oferta.
 * {@code plan_item} se lleva por {@code oferta_id} y no existe puente Oferta-Practica en el
 * esquema. Es un puente <b>observado</b>, no <b>configurado</b>, y un plan planifica antes de que
 * ninguna sesion exista. Detalle en el challenge de la etapa, seccion 9.
 *
 * <h2>De que cuelga, y de que no</h2>
 *
 * <p>Solo de la Sesion. <b>No lleva {@code caso_id} ni {@code historia_clinica_id}</b>: se
 * resuelven por la sesion y duplicarlos habilitaria que discrepen. Es el mismo criterio con el que
 * {@link Sesion} no guarda {@code persona_id}.
 *
 * <h2>El snapshot no es redundancia</h2>
 *
 * <p>Se guardan el id <b>y</b> el codigo y el nombre congelados, de la practica y del espacio. Lo
 * pide {@code resource.spi.CatalogoSnapshot} con esas palabras y RN-M04-003 para el espacio: una
 * sesion de marzo leida en septiembre tiene que decir que practica fue <b>en marzo</b>.
 */
@Entity
@Table(name = "tratamiento_realizado")
public class TratamientoRealizado {

	/** Tope de {@code duracion_minutos} en V55: un dia. */
	public static final int MAX_DURACION_MINUTOS = 1440;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "sesion_id", nullable = false, updatable = false)
	private Long sesionId;

	/**
	 * Secuencia cronologica dentro de la visita. <b>Monotono y no reutilizable.</b>
	 *
	 * <p>Una baja no libera su numero: el proximo sale del maximo sobre <b>todas</b> las filas de
	 * la sesion, vivas y muertas. Por eso {@code uk_tratamiento_orden} lleva {@code deleted_key}.
	 *
	 * <p><b>No hay operacion de reordenar.</b> Intercambiar dos {@code orden} bajo un unique
	 * exigiria un valor intermedio o un {@code DEFERRABLE} que MySQL no tiene, y el dato no lo
	 * justifica: el orden es cronologico —en que secuencia se aplicaron las intervenciones—, no
	 * una preferencia de presentacion. Quien se equivoco da de baja y vuelve a cargar, y el
	 * historico muestra las dos cosas.
	 */
	@Column(name = "orden", nullable = false, updatable = false)
	private int orden;

	@Column(name = "practica_id", nullable = false)
	private Long practicaId;

	@Column(name = "practica_codigo", nullable = false, length = 64)
	private String practicaCodigo;

	@Column(name = "practica_nombre", nullable = false, length = 160)
	private String practicaNombre;

	@Column(name = "tecnica", length = 160)
	private String tecnica;

	@Column(name = "zona", length = 120)
	private String zona;

	@Enumerated(EnumType.STRING)
	@Column(name = "lateralidad", length = 16)
	private Lateralidad lateralidad;

	@Column(name = "duracion_minutos")
	private Integer duracionMinutos;

	/**
	 * Quien aplico <b>esta</b> intervencion. Es la co-atencion de {@code plan_sesiones} 10.5.
	 *
	 * <p><b>Anotar que otro profesional participo no le da permiso de escritura.</b> Quien escribe
	 * sigue siendo unicamente el dueño de la sesion: {@link Sesion#exigirPropiedadDe} se sigue
	 * aplicando y escribir en la atencion ajena sigue siendo <b>409, no 403</b>. El co-atendiente
	 * queda registrado por quien conduce la atencion, que es como funciona en la practica clinica.
	 */
	@Column(name = "profesional_membership_id", nullable = false)
	private Long profesionalMembershipId;

	@Column(name = "espacio_id")
	private Long espacioId;

	@Column(name = "espacio_nombre", length = 160)
	private String espacioNombre;

	@Column(name = "observacion", length = 500)
	private String observacion;

	@Column(name = "registrado_en", nullable = false, updatable = false)
	private Instant registradoEn;

	@Column(name = "registrado_por_cuenta_id", nullable = false, updatable = false)
	private Long registradoPorCuentaId;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Bloqueo optimista de la propia fila.
	 *
	 * <p><b>No es lo que serializa las altas.</b> Eso lo hace el {@code @Version} de
	 * {@link Sesion}, leido con {@code OPTIMISTIC_FORCE_INCREMENT}: escribir un tratamiento no
	 * toca ninguna columna de la sesion, y un {@code @Version} sobre el padre no protege una
	 * escritura que solo toca tablas hijas (02.07, {@code b8bbc67}).
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected TratamientoRealizado() {
		// Requerido por JPA.
	}

	@SuppressWarnings("java:S107")
	public TratamientoRealizado(
			long organizationId,
			long consultorioId,
			long sesionId,
			int orden,
			long practicaId,
			String practicaCodigo,
			String practicaNombre,
			long profesionalMembershipId,
			Instant registradoEn,
			long registradoPorCuentaId) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.sesionId = sesionId;
		this.orden = orden;
		this.practicaId = practicaId;
		this.practicaCodigo = practicaCodigo;
		this.practicaNombre = practicaNombre;
		this.profesionalMembershipId = profesionalMembershipId;
		this.registradoEn = registradoEn;
		this.registradoPorCuentaId = registradoPorCuentaId;
		this.active = true;
		this.createdAt = registradoEn;
		this.updatedAt = registradoEn;
	}

	/**
	 * Reemplaza el contenido clinico de la intervencion.
	 *
	 * <p>No toca {@code orden}, {@code sesionId} ni la autoria del registro: son la identidad del
	 * hecho. Lo que cambia es <b>que</b> se hizo, no <b>cuando</b> en la secuencia ni <b>quien</b>
	 * lo asento.
	 */
	@SuppressWarnings("java:S107")
	public void redefinir(
			long practicaId,
			String practicaCodigo,
			String practicaNombre,
			String tecnica,
			String zona,
			Lateralidad lateralidad,
			Integer duracionMinutos,
			long profesionalMembershipId,
			Long espacioId,
			String espacioNombre,
			String observacion,
			Instant occurredAt) {

		this.practicaId = practicaId;
		this.practicaCodigo = practicaCodigo;
		this.practicaNombre = practicaNombre;
		this.tecnica = tecnica;
		this.zona = zona;
		this.lateralidad = lateralidad;
		this.duracionMinutos = duracionMinutos;
		this.profesionalMembershipId = profesionalMembershipId;
		this.espacioId = espacioId;
		this.espacioNombre = espacioNombre;
		this.observacion = observacion;
		this.updatedAt = occurredAt;
	}

	/**
	 * Baja logica de la intervencion.
	 *
	 * <p><b>El motivo es obligatorio y es el mismo un dato clinico</b> —"se suspendio la
	 * electroterapia porque el paciente refirio molestia"—: sin el, la auditoria no responde por
	 * que seis meses despues. El {@code CHECK} de V55 lo hace cumplir del lado del motor.
	 *
	 * <p>Es idempotente: dar de baja dos veces no cambia el motivo ni el instante originales.
	 * Reescribirlos convertiria el segundo click en una correccion silenciosa del primero.
	 */
	public void darDeBaja(String motivo, Instant occurredAt) {
		if (!active) {
			return;
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = motivo;
		this.updatedAt = occurredAt;
	}

	public boolean estaVigente() {
		return active;
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

	public Long getSesionId() {
		return sesionId;
	}

	public int getOrden() {
		return orden;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public String getPracticaCodigo() {
		return practicaCodigo;
	}

	public String getPracticaNombre() {
		return practicaNombre;
	}

	public String getTecnica() {
		return tecnica;
	}

	public String getZona() {
		return zona;
	}

	public Lateralidad getLateralidad() {
		return lateralidad;
	}

	public Integer getDuracionMinutos() {
		return duracionMinutos;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public Long getEspacioId() {
		return espacioId;
	}

	public String getEspacioNombre() {
		return espacioNombre;
	}

	public String getObservacion() {
		return observacion;
	}

	public Instant getRegistradoEn() {
		return registradoEn;
	}

	public Long getRegistradoPorCuentaId() {
		return registradoPorCuentaId;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeactivationReason() {
		return deactivationReason;
	}

	public long getVersion() {
		return version;
	}
}
