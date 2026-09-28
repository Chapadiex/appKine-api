package com.akine.activity.domain;

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
 * El hecho de que una persona estuvo —o no— en una clase (M28, §30.6 de la spec, RF-M28-007).
 *
 * <h2>Lo que esta fila NO es</h2>
 *
 * <p><b>No es una Sesion clinica.</b> RN-M28-007 lo prohibe sin rodeos: una asistencia no clinica
 * no crea Sesion. Esta clase no conoce {@code clinical} ni {@code encounter}, y no hay una sola
 * columna que apunte hacia alla. La derivacion al circuito clinico es 08.04 y la atencion
 * individual 08.05.
 *
 * <p><b>No convierte a nadie en paciente.</b> Apunta a una {@code persona} y no exige perfil de
 * paciente vigente: haber ido a Pilates no es entrar al circuito clinico (RF-M07-010).
 *
 * <p><b>No devenga nada.</b> RF-M18-008 condiciona la deuda a que la Oferta use esquema
 * {@code POR_CLASE} y a que "la politica" defina que el cargo se devenga con la asistencia, y
 * <b>ninguna de las dos condiciones es evaluable hoy</b>: {@code V24} declara {@code esquema_cobro}
 * como dato "DECLARADO, NO RESUELTO" sin lista cerrada, y la politica no existe en ninguna tabla.
 * Lo que esta fila le deja a 08.06 y 08.07 es la <b>referencia economica unica</b> de la que
 * colgar el devengo cuando exista.
 *
 * <h2>Y lo que esta fila no decide</h2>
 *
 * <p><b>No decide el cupo.</b> Marcar asistencia va de un estado que consume lugar a otro que
 * tambien lo consume —{@code RESERVADA}/{@code CONFIRMADA} -&gt; {@code ASISTIO}/{@code AUSENTE}—
 * asi que {@code cupo_ocupado} no se mueve. Quien otorga el lugar sigue siendo el {@code UPDATE}
 * condicional de 08.02, y la unica operacion de esta etapa que lo llama es el ingreso sin
 * inscripcion, porque esa <b>crea</b> una inscripcion.
 *
 * <h2>Se corrige, no se da de baja</h2>
 *
 * <p>Por eso <b>no hay {@code deletedAt}</b>, a diferencia de {@code ClaseProgramada} y de
 * {@code InscripcionClase}. Con baja logica, el unique {@code (organization_id, clase_id,
 * persona_id)} necesitaria {@code deleted_key} para no estorbar al historico, y entonces
 * <b>dos filas vivas podrian coexistir para la misma persona en la misma clase</b> apenas alguien
 * diera de baja una — que es exactamente la puerta que la referencia economica unica existe para
 * cerrar. La correccion pisa el valor vigente y <b>apendea</b> el anterior en
 * {@link AsistenciaEvento}: nada se borra (regla maestra 10).
 */
@Entity
@Table(name = "asistencia_actividad")
public class AsistenciaActividad {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "clase_id", nullable = false, updatable = false)
	private Long claseId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "inscripcion_id", nullable = false, updatable = false)
	private Long inscripcionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "resultado", nullable = false, length = 16)
	private ResultadoAsistencia resultado;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen", nullable = false, length = 24, updatable = false)
	private OrigenAsistencia origen;

	@Column(name = "profesional_membership_id", updatable = false)
	private Long profesionalMembershipId;

	@Column(name = "observaciones", length = 500)
	private String observaciones;

	@Column(name = "registrada_en", nullable = false, updatable = false)
	private Instant registradaEn;

	@Column(name = "registrada_por_cuenta_id", nullable = false, updatable = false)
	private Long registradaPorCuentaId;

	@Column(name = "corregida_en")
	private Instant corregidaEn;

	@Column(name = "corregida_por_cuenta_id")
	private Long corregidaPorCuentaId;

	@Column(name = "correcciones", nullable = false)
	private int correcciones;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AsistenciaActividad() {
		// Requerido por JPA.
	}

	/**
	 * Registra el hecho por primera vez.
	 *
	 * <p>El instructor llega <b>congelado</b> desde la clase y no se elige por participante: una
	 * clase tiene un profesional, no cuarenta, y un reemplazo es una reprogramacion de la clase,
	 * que ya existe desde 08.01 con su validacion de habilitacion. Se congela por el mismo criterio
	 * que {@code inicio}/{@code fin} en {@code V58}: reprogramar manana no puede cambiar quien
	 * estuvo al frente ayer.
	 */
	public static AsistenciaActividad registrar(
			InscripcionClase inscripcion,
			ResultadoAsistencia resultado,
			OrigenAsistencia origen,
			Long profesionalMembershipId,
			String observaciones,
			long cuentaId,
			Instant occurredAt) {

		AsistenciaActividad asistencia = new AsistenciaActividad();
		asistencia.organizationId = inscripcion.getOrganizationId();
		asistencia.consultorioId = inscripcion.getConsultorioId();
		asistencia.claseId = inscripcion.getClaseId();
		asistencia.personaId = inscripcion.getPersonaId();
		asistencia.inscripcionId = inscripcion.getId();
		asistencia.resultado = resultado;
		asistencia.origen = origen;
		asistencia.profesionalMembershipId = profesionalMembershipId;
		asistencia.observaciones = normalizar(observaciones);
		asistencia.registradaEn = occurredAt;
		asistencia.registradaPorCuentaId = cuentaId;
		asistencia.correcciones = 0;
		return asistencia;
	}

	/**
	 * Corrige un hecho ya afirmado. <b>El motivo es obligatorio.</b>
	 *
	 * <p>Pisa el valor vigente; el anterior queda en {@link AsistenciaEvento}, que es append-only.
	 * No se versionan filas como en 04.02 porque una asistencia no es un documento: es un hecho con
	 * un solo valor vigente, y versionarlo obligaria ademas a decidir cual de las N filas es "la"
	 * referencia economica.
	 *
	 * @return el resultado anterior, para que el llamador pueda apendear el evento
	 * @throws IllegalArgumentException si el motivo falta o si el resultado no cambia
	 */
	public ResultadoAsistencia corregir(
			ResultadoAsistencia nuevo, String observaciones, String motivo,
			long cuentaId, Instant occurredAt) {

		if (nuevo == resultado) {
			throw new IllegalArgumentException(
					"Corregir exige un resultado distinto del vigente: " + resultado);
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"El motivo de la correccion es obligatorio: cambia un hecho ya afirmado");
		}
		ResultadoAsistencia anterior = this.resultado;
		this.resultado = nuevo;
		this.observaciones = normalizar(observaciones);
		this.corregidaEn = occurredAt;
		this.corregidaPorCuentaId = cuentaId;
		this.correcciones++;
		return anterior;
	}

	/** {@code true} si este pedido repite exactamente lo ya registrado y no hay nada que escribir. */
	public boolean repite(ResultadoAsistencia pedido) {
		return resultado == pedido;
	}

	private static String normalizar(String texto) {
		if (texto == null) {
			return null;
		}
		String limpio = texto.strip();
		return limpio.isEmpty() ? null : limpio;
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

	public Long getClaseId() {
		return claseId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Long getInscripcionId() {
		return inscripcionId;
	}

	public ResultadoAsistencia getResultado() {
		return resultado;
	}

	public OrigenAsistencia getOrigen() {
		return origen;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public String getObservaciones() {
		return observaciones;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPorCuentaId() {
		return registradaPorCuentaId;
	}

	public Instant getCorregidaEn() {
		return corregidaEn;
	}

	public Long getCorregidaPorCuentaId() {
		return corregidaPorCuentaId;
	}

	public int getCorrecciones() {
		return correcciones;
	}

	public long getVersion() {
		return version;
	}
}
