package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una VIGENCIA de un codigo dentro de un nomenclador (RF-M06-003, RN-M06-003).
 *
 * <h2>Cada fila es una version, no una correccion</h2>
 *
 * <p>El mismo {@code codigo} dentro del mismo nomenclador puede tener varias filas, cada una
 * con su ventana {@code [validFrom, validUntil)} y su valor de referencia. <b>El historico
 * nunca se pisa: se cierra.</b> Actualizar el valor de un codigo es cerrar la vigencia
 * anterior y abrir una nueva, y eso es lo que permite que un convenio de 2024 siga resolviendo
 * el valor de 2024 despues de que el de 2026 lo reemplace — que es literalmente lo que
 * RN-M06-003 pide.
 *
 * <p>{@code name} se guarda por vigencia y no en la practica: los nomencladores renombran sus
 * codigos, y el nombre con el que se facturo en 2024 es parte del hecho historico.
 *
 * <h2>{@code valorReferencia} NO es el arancel</h2>
 *
 * <p>Es lo que el nomenclador publica —galenos, unidades, un valor testigo—. El arancel
 * cobrable lo fija el convenio (M16) y puede apartarse de este. Confundirlos haria que cambiar
 * el nomenclador cambie lo que un centro cobra, que es exactamente lo que un convenio existe
 * para evitar.
 *
 * <p>Es {@code BigDecimal} y jamas {@code double}: el punto flotante acumula error de redondeo
 * y rompe el cierre de caja. ArchUnit lo verifica sobre todo el paquete {@code domain}.
 *
 * <h2>El solapamiento no lo puede sostener la base</h2>
 *
 * <p>"Dos vigencias del mismo codigo no se pisan" es una restriccion de exclusion sobre rangos:
 * MySQL 8.4 no las tiene. La comparacion vive en {@link #seSolapaCon(Instant, Instant)}, que se
 * hereda, y la <b>serializacion</b> de dos altas simultaneas la da el lock exclusivo sobre la
 * fila del nomenclador padre, tomado antes de consultar. Ver {@code CatalogoService}.
 */
@Entity
@Table(name = "nomenclador_item")
public class NomencladorItem extends CatalogoConcepto {

	@Column(name = "nomenclador_id", nullable = false, updatable = false)
	private Long nomencladorId;

	@Column(name = "practica_id", nullable = false, updatable = false)
	private Long practicaId;

	@Column(name = "valor_referencia", precision = 14, scale = 4)
	private BigDecimal valorReferencia;

	protected NomencladorItem() {
		// Requerido por JPA.
	}

	public NomencladorItem(
			Long organizationId,
			Long nomencladorId,
			Long practicaId,
			String codigo,
			String name,
			String descripcion,
			BigDecimal valorReferencia,
			Instant validFrom,
			Instant validUntil) {

		super(organizationId, codigo, name, descripcion, validFrom, validUntil);
		if (nomencladorId == null || practicaId == null) {
			throw new IllegalArgumentException(
					"Una vigencia de nomenclador exige el nomenclador y la practica que codifica");
		}
		this.nomencladorId = nomencladorId;
		this.practicaId = practicaId;
		this.valorReferencia = exigirValorNoNegativo(valorReferencia);
	}

	/**
	 * Cambia el valor publicado para esta vigencia.
	 *
	 * <p>Existe para corregir una carga —un valor tipeado mal el mismo dia— y no para
	 * actualizar el nomenclador: eso es una vigencia nueva. La diferencia esta en el tiempo, y
	 * el servicio la sostiene rechazando la edicion de una vigencia ya cerrada.
	 */
	public void cambiarValor(BigDecimal valorReferencia) {
		if (valorReferencia != null) {
			this.valorReferencia = exigirValorNoNegativo(valorReferencia);
		}
	}

	private static BigDecimal exigirValorNoNegativo(BigDecimal valor) {
		if (valor != null && valor.signum() < 0) {
			throw new IllegalArgumentException(
					"El valor de referencia de un codigo no puede ser negativo");
		}
		return valor;
	}

	public Long getNomencladorId() {
		return nomencladorId;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public BigDecimal getValorReferencia() {
		return valorReferencia;
	}
}
