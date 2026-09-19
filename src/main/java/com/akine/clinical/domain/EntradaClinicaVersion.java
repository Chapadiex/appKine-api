package com.akine.clinical.domain;

import com.akine.clinical.domain.exception.EnmiendaSinMotivoException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * El contenido de una version de {@link EntradaClinica} (RF-M09-006).
 *
 * <h2>Esta fila no cambia y no se da de baja. Nunca</h2>
 *
 * <p>No hay ni un metodo que modifique {@link #cuerpo}, no hay {@code active}, no hay
 * {@code deleted_at} y no hay {@code @Version}. Las cuatro ausencias son la misma decision: una
 * version es un <b>hecho pasado</b>. Corregirla seria el {@code UPDATE} que RF-M09-006 evita, y
 * darla de baja seria reescribir historia clinica, que ADR-0011 prohibe.
 *
 * <p>Corregir una entrada es <b>enmendarla</b>: se escribe una version nueva con su motivo, y las
 * dos quedan con su autor y su instante. Es el mismo criterio con el que {@link AntecedenteClinico}
 * se da de baja y se vuelve a registrar en vez de editarse.
 *
 * <h2>Por que el autor es de la version y no de la entrada</h2>
 *
 * <p>Quien enmienda no suele ser quien escribio el original —un supervisor que corrige, un
 * profesional del turno siguiente— y guardar el autor solo en la cabecera perderia exactamente el
 * dato por el que existe el historial. {@link #registradaPor} es por fila.
 *
 * <h2>El motivo, y por que su ausencia es 400</h2>
 *
 * <p>La version 1 no lleva motivo: no enmienda nada. Toda posterior lo exige, aca y en la base
 * ({@code ck_entrada_version_motivo_de_enmienda}). Sin motivo, una enmienda es indistinguible de
 * una correccion de tipeo y el historial deja de servir para lo unico que sirve.
 *
 * <p>Se rechaza con {@link EnmiendaSinMotivoException}, que la capa HTTP mapea a <b>400 y no
 * 409</b>: no hay conflicto de estado, falta un dato del pedido. Un 409 invitaria a reintentar, y
 * reintentar sin motivo vuelve a fallar.
 */
@Entity
@Table(name = "entrada_clinica_version")
public class EntradaClinicaVersion extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant propietario. Es derivable de la entrada y se guarda <b>igual</b>.
	 *
	 * <p>Derivarlo obligaria a un join para filtrar por tenant, y el dia que alguien escriba la
	 * consulta sin ese join tiene una fuga que ningun test de la etapa ve, porque los tests de una
	 * etapa corren con un solo tenant (challenge seccion 3).
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "entrada_clinica_id", nullable = false, updatable = false)
	private Long entradaClinicaId;

	@Column(name = "numero_version", nullable = false, updatable = false)
	private int numeroVersion;

	@Column(name = "cuerpo", nullable = false, length = 8000, updatable = false)
	private String cuerpo;

	@Column(name = "motivo_enmienda", length = 280, updatable = false)
	private String motivoEnmienda;

	@Column(name = "registrada_en", nullable = false, updatable = false)
	private Instant registradaEn;

	@Column(name = "registrada_por", nullable = false, updatable = false)
	private Long registradaPor;

	protected EntradaClinicaVersion() {
		// Requerido por JPA.
	}

	public EntradaClinicaVersion(
			Long organizationId,
			Long entradaClinicaId,
			int numeroVersion,
			String cuerpo,
			String motivoEnmienda,
			Instant registradaEn,
			Long registradaPor) {

		if (numeroVersion < 1) {
			throw new IllegalArgumentException("La numeracion de versiones empieza en 1");
		}
		this.organizationId =
				exigirNoNulo(organizationId, "La version pertenece siempre a una organizacion");
		this.entradaClinicaId =
				exigirNoNulo(entradaClinicaId, "La version pertenece siempre a una entrada");
		this.numeroVersion = numeroVersion;
		this.cuerpo = exigirTexto(cuerpo);
		this.motivoEnmienda = motivoCoherente(numeroVersion, motivoEnmienda, entradaClinicaId);
		this.registradaEn = exigirNoNulo(registradaEn, "La version deja siempre su instante");
		this.registradaPor = exigirNoNulo(registradaPor, "La version deja siempre a su autor");
	}

	/** {@code true} si esta version es una enmienda y no el original. */
	public boolean esEnmienda() {
		return numeroVersion > 1;
	}

	private static String motivoCoherente(
			int numeroVersion, String motivoEnmienda, Long entradaClinicaId) {

		String limpio = motivoEnmienda == null || motivoEnmienda.isBlank()
				? null
				: motivoEnmienda.strip();

		if (numeroVersion == 1) {
			// El original no enmienda nada. Un motivo aca no es un error del usuario —la pantalla
			// no lo pide— asi que se descarta en vez de rechazar el registro clinico entero.
			return null;
		}
		if (limpio == null) {
			throw new EnmiendaSinMotivoException(entradaClinicaId);
		}
		return limpio;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String exigirTexto(String valor) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("Una entrada clinica sin cuerpo no registra nada");
		}
		return valor.strip();
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getEntradaClinicaId() {
		return entradaClinicaId;
	}

	public int getNumeroVersion() {
		return numeroVersion;
	}

	public String getCuerpo() {
		return cuerpo;
	}

	public String getMotivoEnmienda() {
		return motivoEnmienda;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPor() {
		return registradaPor;
	}
}
