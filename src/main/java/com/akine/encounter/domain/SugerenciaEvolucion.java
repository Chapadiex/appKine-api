package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.MotivoObligatorioException;
import com.akine.encounter.domain.exception.SugerenciaYaRespondidaException;
import com.akine.encounter.spi.CitaGuardada;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una sugerencia de revision que el modelo propuso al profesional sobre un Caso Clinico, con su
 * evidencia y la respuesta que recibio (M31 fase 2).
 *
 * <p>Se inserta {@code PENDIENTE} y sale de ese estado <b>una sola vez</b>. No se borra: la baja
 * logica es la convencion del modulo, aunque hoy nada la usa.
 */
@Entity
@Table(name = "sugerencia_evolucion")
public class SugerenciaEvolucion {

	/** Largo maximo del motivo de descarte: el de la columna de V18. */
	public static final int MOTIVO_MAXIMO = 300;

	public enum Respuesta { PENDIENTE, ACEPTADA, DESCARTADA }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "caso_id", nullable = false, updatable = false)
	private Long casoId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Column(name = "generacion_id", nullable = false, updatable = false)
	private UUID generacionId;

	@Column(name = "solicitada_por_cuenta_id", nullable = false, updatable = false)
	private Long solicitadaPorCuentaId;

	@Column(name = "solicitada_en", nullable = false, updatable = false)
	private Instant solicitadaEn;

	@Column(name = "hallazgo_codigo", nullable = false, updatable = false, length = 40)
	private String hallazgoCodigo;

	@Column(name = "texto", nullable = false, updatable = false, length = 280)
	private String texto;

	@Column(name = "fundamento", nullable = false, updatable = false, length = 600)
	private String fundamento;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "citas", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<CitaGuardada> citas;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "sesiones", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Long> sesiones;

	@Column(name = "modelo", nullable = false, updatable = false, length = 80)
	private String modelo;

	@Enumerated(EnumType.STRING)
	@Column(name = "respuesta", nullable = false, length = 12)
	private Respuesta respuesta;

	@Column(name = "respondida_por_cuenta_id")
	private Long respondidaPorCuentaId;

	@Column(name = "respondida_en")
	private Instant respondidaEn;

	@Column(name = "motivo_descarte", length = 300)
	private String motivoDescarte;

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

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected SugerenciaEvolucion() {
		// Requerido por JPA.
	}

	@SuppressWarnings("java:S107")
	public SugerenciaEvolucion(long organizationId, long casoId, long historiaClinicaId,
			UUID generacionId, long solicitadaPorCuentaId, Instant solicitadaEn,
			String hallazgoCodigo, String texto, String fundamento, List<CitaGuardada> citas,
			List<Long> sesiones, String modelo) {
		this.organizationId = organizationId;
		this.casoId = casoId;
		this.historiaClinicaId = historiaClinicaId;
		this.generacionId = generacionId;
		this.solicitadaPorCuentaId = solicitadaPorCuentaId;
		this.solicitadaEn = solicitadaEn;
		this.hallazgoCodigo = hallazgoCodigo;
		this.texto = texto;
		this.fundamento = fundamento;
		this.citas = List.copyOf(citas);
		this.sesiones = List.copyOf(sesiones);
		this.modelo = modelo;
		this.respuesta = Respuesta.PENDIENTE;
		this.active = true;
		this.createdAt = solicitadaEn;
		this.updatedAt = solicitadaEn;
	}

	/**
	 * Registra la respuesta del profesional. Solo desde {@code PENDIENTE}; descartar exige motivo.
	 *
	 * @throws SugerenciaYaRespondidaException si ya tenia respuesta
	 * @throws MotivoObligatorioException si se descarta sin motivo, o el motivo excede el maximo
	 */
	public void responder(Respuesta nueva, String motivo, long cuentaId, Instant cuando) {
		if (nueva == null || nueva == Respuesta.PENDIENTE) {
			throw new IllegalArgumentException("La respuesta debe ser ACEPTADA o DESCARTADA");
		}
		if (respuesta != Respuesta.PENDIENTE) {
			throw new SugerenciaYaRespondidaException(id);
		}
		String motivoFinal = null;
		if (nueva == Respuesta.DESCARTADA) {
			if (motivo == null || motivo.isBlank()) {
				throw new MotivoObligatorioException("motivo", "El motivo es obligatorio al descartar");
			}
			motivoFinal = motivo.strip();
			if (motivoFinal.length() > MOTIVO_MAXIMO) {
				throw new MotivoObligatorioException("motivo",
						"El motivo admite como maximo " + MOTIVO_MAXIMO + " caracteres");
			}
		}
		this.respuesta = nueva;
		this.motivoDescarte = motivoFinal;
		this.respondidaPorCuentaId = cuentaId;
		this.respondidaEn = cuando;
		this.updatedAt = cuando;
	}

	public Long getId() { return id; }
	public Long getOrganizationId() { return organizationId; }
	public Long getCasoId() { return casoId; }
	public Long getHistoriaClinicaId() { return historiaClinicaId; }
	public UUID getGeneracionId() { return generacionId; }
	public Long getSolicitadaPorCuentaId() { return solicitadaPorCuentaId; }
	public Instant getSolicitadaEn() { return solicitadaEn; }
	public String getHallazgoCodigo() { return hallazgoCodigo; }
	public String getTexto() { return texto; }
	public String getFundamento() { return fundamento; }
	public List<CitaGuardada> getCitas() { return citas; }
	public List<Long> getSesiones() { return sesiones; }
	public String getModelo() { return modelo; }
	public Respuesta getRespuesta() { return respuesta; }
	public Long getRespondidaPorCuentaId() { return respondidaPorCuentaId; }
	public Instant getRespondidaEn() { return respondidaEn; }
	public String getMotivoDescarte() { return motivoDescarte; }
	public boolean isActive() { return active; }
	public long getVersion() { return version; }
}
