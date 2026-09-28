package com.akine.clinical.domain;

import com.akine.clinical.spi.OrigenDeParticipacion;
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
 * El vinculo auditable entre una participacion grupal y su contexto clinico (RF-M28-008).
 *
 * <h2>Esto NO es una atencion, y toda la etapa depende de que se mantenga asi</h2>
 *
 * <p>Hay cuatro hechos distintos y esta entidad es el tercero: la inscripcion reserva un lugar
 * (08.02), la asistencia afirma que la persona estuvo (08.03), <b>la derivacion afirma que esa
 * participacion pertenece a este Caso</b>, y la Sesion documenta la atencion prestada (08.05).
 * Ninguna transicion administrativa prueba por si sola que hubo prestacion (DP-05), y esta fila no
 * es la excepcion: no numera nada dentro del Caso, no descuenta una unidad de autorizacion y no
 * llega al timeline clinico.
 *
 * <p><b>08.04 abre la puerta, 08.05 entra.</b>
 *
 * <h2>Por que vive en clinical y no en activity</h2>
 *
 * <p>Porque "de donde vino este paciente y por que esta en este Caso" es un hecho clinico, y
 * tocarlo —leerlo incluido— tiene que pasar por la politica de DP-03: permiso, relacion
 * asistencial o justificacion declarada, y auditoria. Esa politica es privada de
 * {@code clinical.application}. Con la fila en {@code activity} habria dos implementaciones de
 * DP-03 y solo una se acordaria de auditar la lectura. Ver la cabecera de {@code V63}, punto 2.
 *
 * <h2>Lo que esta congelado y por que</h2>
 *
 * <p>{@code ofertaId} y {@code requiereCasoClinico} se copian al derivar. Si manana alguien le
 * apaga {@code genera_registro_clinico} a la oferta, esta fila sigue explicando por que se derivo
 * entonces — que es lo que la auditoria necesita y lo que una lectura en vivo de la oferta ya no
 * podria contestar.
 */
@Entity
@Table(name = "derivacion_clinica")
public class DerivacionClinica {

	/** Tope del motivo, en la entidad y en la columna. */
	public static final int MOTIVO_MAXIMO = 300;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen", nullable = false, length = 24, updatable = false)
	private OrigenDeParticipacion origen;

	@Column(name = "participacion_id", nullable = false, updatable = false)
	private Long participacionId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Column(name = "caso_clinico_id", nullable = false, updatable = false)
	private Long casoClinicoId;

	@Column(name = "plan_tratamiento_id", updatable = false)
	private Long planTratamientoId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "requiere_caso_clinico", nullable = false, updatable = false)
	private boolean requiereCasoClinico;

	@Column(name = "autorizacion_id", updatable = false)
	private Long autorizacionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoDerivacion estado;

	@Column(name = "motivo", length = MOTIVO_MAXIMO, updatable = false)
	private String motivo;

	@Column(name = "derivada_en", nullable = false, updatable = false)
	private Instant derivadaEn;

	@Column(name = "derivada_por_cuenta_id", nullable = false, updatable = false)
	private Long derivadaPorCuentaId;

	@Column(name = "revertida_en")
	private Instant revertidaEn;

	@Column(name = "revertida_por_cuenta_id")
	private Long revertidaPorCuentaId;

	@Column(name = "motivo_reversion", length = MOTIVO_MAXIMO)
	private String motivoReversion;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected DerivacionClinica() {
	}

	/**
	 * Abre el vinculo. Nace {@link EstadoDerivacion#VIGENTE} y no hay otra forma de nacer.
	 *
	 * <p>Todas las referencias llegan <b>ya validadas</b> por {@code DerivacionClinicaService}: la
	 * historia asegurada, el Caso activo y de esa historia, el plan de ese Caso, la autorizacion
	 * habilitante. Esta clase no revalida porque no puede: no tiene repositorios.
	 */
	public static DerivacionClinica abrir(
			long organizationId,
			long consultorioId,
			OrigenDeParticipacion origen,
			long participacionId,
			long personaId,
			long historiaClinicaId,
			long casoClinicoId,
			Long planTratamientoId,
			long ofertaId,
			boolean requiereCasoClinico,
			Long autorizacionId,
			String motivo,
			Instant ocurridoEn,
			long actorCuentaId) {

		DerivacionClinica derivacion = new DerivacionClinica();
		derivacion.organizationId = organizationId;
		derivacion.consultorioId = consultorioId;
		derivacion.origen = exigirNoNulo(origen, "El origen de la participacion es obligatorio");
		derivacion.participacionId = participacionId;
		derivacion.personaId = personaId;
		derivacion.historiaClinicaId = historiaClinicaId;
		derivacion.casoClinicoId = casoClinicoId;
		derivacion.planTratamientoId = planTratamientoId;
		derivacion.ofertaId = ofertaId;
		derivacion.requiereCasoClinico = requiereCasoClinico;
		derivacion.autorizacionId = autorizacionId;
		derivacion.estado = EstadoDerivacion.VIGENTE;
		derivacion.motivo = recortar(motivo);
		derivacion.derivadaEn = exigirNoNulo(ocurridoEn, "El instante de la derivacion es obligatorio");
		derivacion.derivadaPorCuentaId = actorCuentaId;
		derivacion.createdAt = ocurridoEn;
		derivacion.updatedAt = ocurridoEn;
		return derivacion;
	}

	/**
	 * Deshace el vinculo. El motivo es <b>obligatorio</b>: deshacer sin decir por que no es
	 * auditable.
	 *
	 * <p>Revertir una derivacion ya revertida devuelve {@code false} y no toca nada — el reintento
	 * despues de un timeout no es un error, y el segundo motivo no pisa al primero.
	 *
	 * <h2>El cerrojo que falta, y le toca a 08.05</h2>
	 *
	 * <p>Hoy <b>cualquier derivacion vigente se puede revertir</b>, y eso va a estar mal en cuanto
	 * exista 08.05: revertir una de la que cuelga una Sesion cerrada dejaria una atencion clinica
	 * documentada apuntando a un vinculo deshecho. Hoy no cuelga ninguna, porque las Sesiones de
	 * origen grupal son de esa etapa. Construir ahora una sonda contra una tabla que no existe
	 * seria anticipar en el esquema la decision de otra etapa — que es justo lo que 08.03 se nego a
	 * hacer cuando no dejo una columna {@code derivable}. <b>08.05 tiene que cerrarlo.</b>
	 *
	 * @return {@code true} si esta llamada revirtio de verdad
	 */
	public boolean revertir(String motivo, Instant ocurridoEn, long actorCuentaId) {
		if (estado == EstadoDerivacion.REVERTIDA) {
			return false;
		}
		String limpio = recortar(motivo);
		if (limpio == null) {
			throw new IllegalArgumentException("Revertir una derivacion exige motivo");
		}
		this.estado = EstadoDerivacion.REVERTIDA;
		this.motivoReversion = limpio;
		this.revertidaEn = ocurridoEn;
		this.revertidaPorCuentaId = actorCuentaId;
		this.updatedAt = ocurridoEn;
		return true;
	}

	/** {@code true} si la derivacion es de esa organizacion. */
	public boolean perteneceA(long organizationId) {
		return this.organizationId != null && this.organizationId == organizationId;
	}

	/** {@code true} si cuelga de ese Caso. */
	public boolean perteneceACaso(long casoClinicoId) {
		return this.casoClinicoId != null && this.casoClinicoId == casoClinicoId;
	}

	private static String recortar(String texto) {
		if (texto == null || texto.isBlank()) {
			return null;
		}
		String limpio = texto.strip();
		return limpio.length() > MOTIVO_MAXIMO ? limpio.substring(0, MOTIVO_MAXIMO) : limpio;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public OrigenDeParticipacion getOrigen() {
		return origen;
	}

	public Long getParticipacionId() {
		return participacionId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Long getHistoriaClinicaId() {
		return historiaClinicaId;
	}

	public Long getCasoClinicoId() {
		return casoClinicoId;
	}

	public Long getPlanTratamientoId() {
		return planTratamientoId;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public boolean isRequiereCasoClinico() {
		return requiereCasoClinico;
	}

	public Long getAutorizacionId() {
		return autorizacionId;
	}

	public EstadoDerivacion getEstado() {
		return estado;
	}

	public String getMotivo() {
		return motivo;
	}

	public Instant getDerivadaEn() {
		return derivadaEn;
	}

	public Long getDerivadaPorCuentaId() {
		return derivadaPorCuentaId;
	}

	public Instant getRevertidaEn() {
		return revertidaEn;
	}

	public Long getRevertidaPorCuentaId() {
		return revertidaPorCuentaId;
	}

	public String getMotivoReversion() {
		return motivoReversion;
	}

	public long getVersion() {
		return version;
	}
}
