package com.akine.clinical.domain;

import com.akine.clinical.domain.exception.CasoClinicoCerradoException;
import com.akine.clinical.domain.exception.CierreDeCasoSinMotivoException;
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
 * Un problema clinico concreto de un paciente, dentro de su Historia Clinica (M10, RF-M10-001).
 *
 * <h2>Historia Clinica != Caso != Sesion</h2>
 *
 * <p>Es la regla maestra 1, y esta clase es donde se hace verdad. La <b>historia</b> es el
 * contexto longitudinal del paciente en la organizacion y existe sin casos. El <b>caso</b> es un
 * problema concreto —una rodilla, un hombro— y agrupa las sesiones que lo trataron. La
 * <b>sesion</b> es una atencion que ocurrio, vive en {@code encounter} y esta clase no la toca.
 *
 * <p>Varios casos activos a la vez son legitimos (RN-M10-002) y <b>no hay unique que lo impida</b>:
 * una rodilla y un hombro son dos casos del mismo paciente el mismo dia. El duplicado razonable
 * —misma oferta, caso activo— lo detecta la aplicacion y se confirma reenviando, como el alta de
 * Persona de 03.01. Un unique aca seria un bug disfrazado de proteccion.
 *
 * <h2>Lo que esta clase NO tiene, y que es la mitad de su diseño</h2>
 *
 * <p><b>No tiene contador de sesiones.</b> Lo tiene {@code caso_sesion_numerador}, que es un
 * numerador y no un {@code COUNT(*)} cacheado: un cache se desincroniza y un numerador no puede,
 * porque nunca vuelve atras. La distincion es la misma que {@code sesion_numerador} hizo en 06.05.
 *
 * <p><b>No tiene {@code active} ni baja logica.</b> Cerrar no es borrar. Un caso abierto por error
 * se cierra con motivo; agregar {@code active} al lado de {@link #estado} daria dos formas de que
 * un caso "no este", que es como se construye una consulta que se olvida de una.
 *
 * <p><b>No tiene {@code consultorioId}.</b> El caso cuelga de la historia, y la historia es de la
 * <b>organizacion</b> (DP-03): un caso de sede seria un caso que no se puede continuar en la otra
 * sede del mismo centro. Lo que si guarda es {@link #ofertaConsultorioId}, que es la sede de la
 * <b>oferta</b> —las ofertas son por sede desde V24— y sin la cual {@link #ofertaId} no se puede
 * volver a resolver.
 *
 * <p><b>No tiene Plan de Tratamiento.</b> Es 04.04, y la regla maestra 2 lo separa del Caso y del
 * Turno.
 */
@Entity
@Table(name = "caso_clinico")
public class CasoClinico extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Historia de la que cuelga. {@code Long} y no {@code @ManyToOne} por el mismo criterio que el
	 * resto del modulo: la relacion se resuelve por id y la carga la decide quien consulta.
	 */
	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	/**
	 * Correlativo dentro de la historia. Lo asigna {@code caso_numerador} con
	 * {@code UPDATE ultimo_numero + 1}, <b>nunca</b> con {@code MAX + 1}: dos altas simultaneas
	 * del mismo paciente se llevarian el mismo numero.
	 */
	@Column(name = "numero_caso", nullable = false, updatable = false)
	private Integer numeroCaso;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "oferta_consultorio_id", nullable = false, updatable = false)
	private Long ofertaConsultorioId;

	@Column(name = "diagnostico_presuntivo", nullable = false, length = 500)
	private String diagnosticoPresuntivo;

	@Column(name = "objetivo_terapeutico", length = 1000)
	private String objetivoTerapeutico;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoCaso estado;

	@Column(name = "abierto_en", nullable = false, updatable = false)
	private Instant abiertoEn;

	@Column(name = "abierto_por", nullable = false, updatable = false)
	private Long abiertoPor;

	@Column(name = "cerrado_en")
	private Instant cerradoEn;

	@Column(name = "cerrado_por")
	private Long cerradoPor;

	@Column(name = "motivo_cierre", length = 500)
	private String motivoCierre;

	/**
	 * Bloqueo optimista, y no es un adorno.
	 *
	 * <p>Dos profesionales del equipo editando el objetivo del mismo caso son el caso normal, no
	 * el raro (RF-M10-004). Sin esto, el segundo pisa al primero en silencio.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CasoClinico() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public CasoClinico(
			Long organizationId,
			Long historiaClinicaId,
			int numeroCaso,
			Long ofertaId,
			Long ofertaConsultorioId,
			String diagnosticoPresuntivo,
			String objetivoTerapeutico,
			Instant abiertoEn,
			Long abiertoPor) {

		this.organizationId = exigirNoNulo(organizationId, "El caso pertenece a una organizacion");
		this.historiaClinicaId =
				exigirNoNulo(historiaClinicaId, "El caso cuelga siempre de una historia clinica");
		if (numeroCaso <= 0) {
			throw new IllegalArgumentException("El correlativo del caso es positivo");
		}
		this.numeroCaso = numeroCaso;
		this.ofertaId = exigirNoNulo(ofertaId, "El caso declara siempre la oferta que lo motiva");
		this.ofertaConsultorioId =
				exigirNoNulo(ofertaConsultorioId, "La oferta se resuelve siempre dentro de una sede");
		this.diagnosticoPresuntivo = exigirTexto(
				diagnosticoPresuntivo, "El caso exige un diagnostico presuntivo");
		this.objetivoTerapeutico = vacioEsNulo(objetivoTerapeutico);
		this.estado = EstadoCaso.ACTIVO;
		this.abiertoEn = exigirNoNulo(abiertoEn, "La apertura registra siempre su instante");
		this.abiertoPor = exigirNoNulo(abiertoPor, "La apertura registra siempre a su actor");
	}

	/**
	 * Edita el contenido clinico del caso (RF-M10-004).
	 *
	 * <p><b>Un caso cerrado no admite edicion de contenido clinico:</b> 409. Corregir lo que dice
	 * un caso terminado sin dejar rastro es historia clinica reescrita en silencio, y lo que
	 * corresponde es reabrirlo con motivo — que es otra operacion y queda en el historial.
	 *
	 * <p>El diagnostico no se puede vaciar: un caso sin el es una fila que en la lista de casos del
	 * paciente no se distingue de la de al lado. El objetivo si, porque puede no estar definido
	 * todavia.
	 */
	public void editar(String diagnosticoPresuntivo, String objetivoTerapeutico) {
		exigirActivo();
		this.diagnosticoPresuntivo = exigirTexto(
				diagnosticoPresuntivo, "El caso exige un diagnostico presuntivo");
		this.objetivoTerapeutico = vacioEsNulo(objetivoTerapeutico);
	}

	/**
	 * Cierra el caso con motivo declarado (RF-M10-006).
	 *
	 * <p><b>El motivo es obligatorio y lo exige la entidad, no el DTO.</b> Sin motivo, un cierre es
	 * indistinguible de un abandono y el historial deja de servir para lo unico que sirve. Que la
	 * regla viva aca y no solo en el request significa que ningun camino de escritura —ni uno
	 * futuro que no pase por el controller— puede cerrar un caso sin explicar por que.
	 *
	 * <p><b>Cerrar dos veces no es un conflicto:</b> es el mismo pedido, y el motivo original queda
	 * intacto. Pisarlo con el nuevo perderia el que explica el cierre, igual que en la baja de una
	 * entrada clinica.
	 *
	 * @return {@code true} si este llamado produjo el cierre; {@code false} si ya estaba cerrado
	 */
	public boolean cerrar(String motivo, Instant occurredAt, Long actorAccountId) {
		String limpio = vacioEsNulo(motivo);
		if (limpio == null) {
			throw new CierreDeCasoSinMotivoException(id);
		}
		if (estado == EstadoCaso.CERRADO) {
			return false;
		}
		this.estado = EstadoCaso.CERRADO;
		this.cerradoEn = exigirNoNulo(occurredAt, "El cierre registra siempre su instante");
		this.cerradoPor = exigirNoNulo(actorAccountId, "El cierre registra siempre a su actor");
		this.motivoCierre = limpio;
		return true;
	}

	/**
	 * Reabre un caso cerrado, con motivo (RF-M10-006).
	 *
	 * <p>Las tres columnas del cierre se limpian porque el CHECK
	 * {@code ck_caso_clinico_cierre_coherente} no admite un caso ACTIVO con instante de cierre, y
	 * porque una consulta por {@code cerrado_en IS NOT NULL} devolveria casos abiertos. <b>No se
	 * pierde nada</b>: el cierre anterior, con su motivo y su actor, esta en {@code caso_evento},
	 * que es append-only.
	 *
	 * <p><b>Reabrir no reinicia la numeracion de sesiones del caso.</b> La sesion siguiente a una
	 * reapertura es la 9, no la 1: renumerar seria reescribir historia clinica (challenge, quinta
	 * condicion).
	 *
	 * @return {@code true} si este llamado produjo la reapertura; {@code false} si ya estaba activo
	 */
	public boolean reabrir(String motivo, Instant occurredAt) {
		String limpio = vacioEsNulo(motivo);
		if (limpio == null) {
			throw new CierreDeCasoSinMotivoException(id);
		}
		if (estado == EstadoCaso.ACTIVO) {
			return false;
		}
		this.estado = EstadoCaso.ACTIVO;
		this.cerradoEn = null;
		this.cerradoPor = null;
		this.motivoCierre = null;
		exigirNoNulo(occurredAt, "La reapertura registra siempre su instante");
		return true;
	}

	/** {@code true} mientras el caso admita sesiones, ediciones y cambios de equipo. */
	public boolean estaActivo() {
		return estado == EstadoCaso.ACTIVO;
	}

	/**
	 * Exige que el caso admita cambios.
	 *
	 * <p>Es <b>409 y no 403</b>: quien opera tiene el permiso: lo que no admite la operacion es el
	 * estado del caso. Un 403 lo mandaria a pedirle a su administrador un permiso que ya tiene.
	 */
	public void exigirActivo() {
		if (estado != EstadoCaso.ACTIVO) {
			throw new CasoClinicoCerradoException(id);
		}
	}

	/** {@code true} si el caso pertenece a esa historia. Otra historia se trata como inexistente. */
	public boolean perteneceAHistoria(long historiaClinicaId) {
		return this.historiaClinicaId != null && this.historiaClinicaId == historiaClinicaId;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String exigirTexto(String valor, String mensaje) {
		String limpio = vacioEsNulo(valor);
		if (limpio == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return limpio;
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

	public Long getHistoriaClinicaId() {
		return historiaClinicaId;
	}

	public Integer getNumeroCaso() {
		return numeroCaso;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public Long getOfertaConsultorioId() {
		return ofertaConsultorioId;
	}

	public String getDiagnosticoPresuntivo() {
		return diagnosticoPresuntivo;
	}

	public String getObjetivoTerapeutico() {
		return objetivoTerapeutico;
	}

	public EstadoCaso getEstado() {
		return estado;
	}

	public Instant getAbiertoEn() {
		return abiertoEn;
	}

	public Long getAbiertoPor() {
		return abiertoPor;
	}

	public Instant getCerradoEn() {
		return cerradoEn;
	}

	public Long getCerradoPor() {
		return cerradoPor;
	}

	public String getMotivoCierre() {
		return motivoCierre;
	}

	public long getVersion() {
		return version;
	}
}
