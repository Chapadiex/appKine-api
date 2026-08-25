package com.akine.resource.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las reglas del catalogo clinico que se pueden decidir <b>sin base de datos</b> (M06).
 *
 * <p>Mismo reparto que 02.02 dejo entre {@code EspacioTest} y {@code EspaciosIT}: lo que depende
 * de estado persistido —uniques, aislamiento, concurrencia— se prueba contra MySQL real en
 * {@code CatalogosIT}; lo que es aritmetica de intervalos y validacion de invariantes se prueba
 * aca, donde cuesta milisegundos en vez de minutos de Testcontainers.
 *
 * <p>La regla que mas se ejercita es {@link CatalogoConcepto#seSolapaCon(Instant, Instant)},
 * porque es la unica que la base <b>no puede</b> sostener —MySQL 8.4 no tiene restricciones de
 * exclusion sobre rangos— y de la que depende que la pregunta "que decia este codigo el dia D"
 * tenga una sola respuesta (RN-M06-003).
 */
class CatalogoConceptoTest {

	private static final Instant AHORA = Instant.parse("2026-06-01T00:00:00Z");
	private static final Instant EN_UN_MES = AHORA.plus(30, ChronoUnit.DAYS);
	private static final Instant EN_DOS_MESES = AHORA.plus(60, ChronoUnit.DAYS);

	// =================================================================================
	// Duenio: global contra contextual
	// =================================================================================

	@Nested
	@DisplayName("Duenio")
	class Duenio {

		@Test
		@DisplayName("Sin organizacion el concepto es del catalogo de plataforma")
		void sin_organizacion_es_global() {
			assertThat(especialidad(null).esGlobal()).isTrue();
			assertThat(CatalogoAlcance.de(null)).isEqualTo(CatalogoAlcance.GLOBAL);
		}

		@Test
		@DisplayName("Con organizacion el concepto es propio de ese tenant")
		void con_organizacion_es_contextual() {
			assertThat(especialidad(7L).esGlobal()).isFalse();
			assertThat(especialidad(7L).getOrganizationId()).isEqualTo(7L);
			assertThat(CatalogoAlcance.de(7L)).isEqualTo(CatalogoAlcance.ORGANIZACION);
		}
	}

	// =================================================================================
	// Vigencia: los dos ejes temporales
	// =================================================================================

	@Nested
	@DisplayName("Vigencia")
	class Vigencia {

		@Test
		@DisplayName("El inicio es inclusivo y el fin exclusivo, para que dos ventanas "
				+ "consecutivas no se pisen en el borde")
		void los_bordes_de_la_ventana() {
			Especialidad concepto = new Especialidad(
					1L, "COD", "Nombre", null, AHORA, EN_UN_MES);

			assertThat(concepto.estaVigente(AHORA.minusMillis(1)))
					.as("un instante antes del inicio todavia no se puede elegir")
					.isFalse();
			assertThat(concepto.estaVigente(AHORA))
					.as("el inicio es inclusivo")
					.isTrue();
			assertThat(concepto.estaVigente(EN_UN_MES.minusMillis(1))).isTrue();
			assertThat(concepto.estaVigente(EN_UN_MES))
					.as("el fin es EXCLUSIVO: si no, dos vigencias consecutivas se solaparian "
							+ "en el microsegundo del borde")
					.isFalse();
		}

		@Test
		@DisplayName("Sin fin de vigencia el concepto se puede elegir para siempre")
		void sin_fin_es_abierta() {
			assertThat(especialidad(1L).estaVigente(EN_DOS_MESES)).isTrue();
		}

		@Test
		@DisplayName("Un concepto dado de baja no se puede elegir aunque su ventana lo cubra")
		void la_baja_gana_sobre_la_vigencia() {
			Especialidad concepto = especialidad(1L);
			concepto.deactivate(AHORA, "Se discontinua");

			assertThat(concepto.estaVigente(AHORA))
					.as("ciclo de vida y vigencia son dos ejes, y basta con que uno falle")
					.isFalse();
			assertThat(concepto.isOperable()).isFalse();
		}

		@Test
		@DisplayName("Una ventana que termina antes de empezar no es una ventana")
		void la_ventana_invertida_se_rechaza() {
			assertThatThrownBy(() -> new Especialidad(1L, "COD", "N", null, EN_UN_MES, AHORA))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("posterior");
		}

		@Test
		@DisplayName("Una ventana de duracion cero tampoco, porque el fin es exclusivo")
		void la_ventana_vacia_se_rechaza() {
			assertThatThrownBy(() -> new Especialidad(1L, "COD", "N", null, AHORA, AHORA))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Sin inicio explicito no hay vigencia que evaluar")
		void el_inicio_es_obligatorio() {
			assertThatThrownBy(() -> new Especialidad(1L, "COD", "N", null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("inicio");
		}
	}

	// =================================================================================
	// Solapamiento: el invariante que la base no puede sostener
	// =================================================================================

	@Nested
	@DisplayName("Solapamiento de vigencias")
	class Solapamiento {

		@Test
		@DisplayName("Dos ventanas consecutivas NO se solapan: cerrar en T y abrir en T es el "
				+ "camino normal de una actualizacion de valores")
		void consecutivas_no_se_solapan() {
			assertThat(vigencia(AHORA, EN_UN_MES).seSolapaCon(EN_UN_MES, EN_DOS_MESES)).isFalse();
			assertThat(vigencia(EN_UN_MES, EN_DOS_MESES).seSolapaCon(AHORA, EN_UN_MES)).isFalse();
		}

		@Test
		@DisplayName("Dos ventanas que comparten cualquier instante interior si se solapan")
		void las_que_se_pisan_se_detectan() {
			NomencladorItem existente = vigencia(AHORA, EN_DOS_MESES);

			assertThat(existente.seSolapaCon(EN_UN_MES, EN_DOS_MESES.plusMillis(1)))
					.as("la nueva empieza adentro de la vieja")
					.isTrue();
			assertThat(existente.seSolapaCon(AHORA.minusMillis(1), EN_UN_MES))
					.as("la nueva termina adentro de la vieja")
					.isTrue();
			assertThat(existente.seSolapaCon(AHORA.minusMillis(1), EN_DOS_MESES.plusMillis(1)))
					.as("la nueva contiene a la vieja")
					.isTrue();
		}

		@Test
		@DisplayName("Una ventana abierta se solapa con todo lo que empiece despues de su inicio")
		void la_ventana_abierta_lo_cubre_todo_hacia_adelante() {
			NomencladorItem abierta = vigencia(AHORA, null);

			assertThat(abierta.seSolapaCon(EN_DOS_MESES, null)).isTrue();
			assertThat(abierta.seSolapaCon(EN_UN_MES, EN_DOS_MESES)).isTrue();
			assertThat(abierta.seSolapaCon(AHORA.minus(60, ChronoUnit.DAYS), AHORA))
					.as("pero no con lo que TERMINA justo cuando ella empieza")
					.isFalse();
		}
	}

	// =================================================================================
	// Edicion parcial
	// =================================================================================

	@Nested
	@DisplayName("Edicion")
	class Edicion {

		@Test
		@DisplayName("Un campo omitido no se toca")
		void los_nulos_no_tocan_nada() {
			Especialidad concepto = new Especialidad(
					1L, "COD", "Original", "Descripcion original", AHORA, EN_UN_MES);

			concepto.updateDatos(null, null, null, null, false);

			assertThat(concepto.getName()).isEqualTo("Original");
			assertThat(concepto.getDescripcion()).isEqualTo("Descripcion original");
			assertThat(concepto.getValidFrom()).isEqualTo(AHORA);
			assertThat(concepto.getValidUntil()).isEqualTo(EN_UN_MES);
		}

		@Test
		@DisplayName("La cadena vacia borra la descripcion; null la deja como estaba")
		void la_cadena_vacia_borra() {
			Especialidad concepto = new Especialidad(
					1L, "COD", "N", "Algo", AHORA, null);

			concepto.updateDatos(null, "   ", null, null, false);

			assertThat(concepto.getDescripcion())
					.as("un PATCH no puede borrar mandando null, asi que borra con vacio")
					.isNull();
		}

		@Test
		@DisplayName("clearValidUntil deja la vigencia abierta, y gana sobre validUntil")
		void limpiar_el_fin_gana_sobre_el_valor() {
			Especialidad concepto = new Especialidad(
					1L, "COD", "N", null, AHORA, EN_UN_MES);

			concepto.updateDatos(null, null, null, EN_DOS_MESES, true);

			assertThat(concepto.getValidUntil())
					.as("'sacale el fin' y 'ponele este fin' son dos intenciones distintas, y la "
							+ "explicita gana")
					.isNull();
		}

		@Test
		@DisplayName("Sin clearValidUntil, un fin nuevo reemplaza al anterior")
		void el_fin_nuevo_reemplaza() {
			Especialidad concepto = new Especialidad(1L, "COD", "N", null, AHORA, EN_UN_MES);

			concepto.updateDatos("Otro nombre", null, AHORA, EN_DOS_MESES, false);

			assertThat(concepto.getName()).isEqualTo("Otro nombre");
			assertThat(concepto.getValidUntil()).isEqualTo(EN_DOS_MESES);
		}

		@Test
		@DisplayName("Una edicion no puede dejar la vigencia incoherente")
		void la_edicion_revalida_la_ventana() {
			Especialidad concepto = new Especialidad(1L, "COD", "N", null, AHORA, EN_UN_MES);

			assertThatThrownBy(() -> concepto.updateDatos(null, null, EN_DOS_MESES, null, false))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Un nombre en blanco se rechaza al editar, igual que al crear")
		void el_nombre_en_blanco_se_rechaza() {
			Especialidad concepto = especialidad(1L);

			assertThatThrownBy(() -> concepto.updateDatos("   ", null, null, null, false))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	@Nested
	@DisplayName("Baja logica")
	class Baja {

		@Test
		@DisplayName("La baja deja el concepto legible, con su motivo y su instante")
		void la_baja_conserva_todo() {
			Especialidad concepto = especialidad(1L);

			concepto.deactivate(AHORA, "  Reemplazada por el nomenclador 2026  ");

			assertThat(concepto.isActive()).isFalse();
			assertThat(concepto.getDeletedAt()).isEqualTo(AHORA);
			assertThat(concepto.getDeactivationReason())
					.isEqualTo("Reemplazada por el nomenclador 2026");
			assertThat(concepto.getName())
					.as("RN-M06-002: el historico conserva su nombre")
					.isEqualTo("Kinesiologia");
			assertThat(concepto.getCodigo()).isEqualTo("KIN");
		}

		@Test
		@DisplayName("Sin motivo declarado no hay baja")
		void el_motivo_es_obligatorio() {
			Especialidad concepto = especialidad(1L);

			assertThatThrownBy(() -> concepto.deactivate(AHORA, "  "))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("motivo");
			assertThatThrownBy(() -> concepto.deactivate(AHORA, null))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(concepto.isActive())
					.as("un rechazo no deja el concepto a medio dar de baja")
					.isTrue();
		}
	}

	// =================================================================================
	// Invariantes propios de cada concepto
	// =================================================================================

	@Nested
	@DisplayName("Invariantes por tipo")
	class PorTipo {

		@Test
		@DisplayName("Toda practica pertenece a una especialidad")
		void la_practica_exige_especialidad() {
			assertThatThrownBy(() ->
					new Practica(1L, null, "COD", "N", null, AHORA, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("especialidad");
		}

		@Test
		@DisplayName("Una vigencia exige su nomenclador y su practica")
		void la_vigencia_exige_sus_referencias() {
			assertThatThrownBy(() -> new NomencladorItem(
					1L, null, 3L, "27.01", "N", null, null, AHORA, null))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new NomencladorItem(
					1L, 2L, null, "27.01", "N", null, null, AHORA, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("El valor de referencia nunca es negativo, ni al crear ni al corregir")
		void el_valor_no_puede_ser_negativo() {
			assertThatThrownBy(() -> new NomencladorItem(
					1L, 2L, 3L, "27.01", "N", null, new BigDecimal("-1"), AHORA, null))
					.isInstanceOf(IllegalArgumentException.class);

			NomencladorItem item = vigencia(AHORA, null);
			assertThatThrownBy(() -> item.cambiarValor(new BigDecimal("-0.01")))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Corregir con null deja el valor como estaba; cero es un valor valido")
		void corregir_con_null_no_borra() {
			NomencladorItem item = new NomencladorItem(
					1L, 2L, 3L, "27.01", "N", null, new BigDecimal("100"), AHORA, null);

			item.cambiarValor(null);
			assertThat(item.getValorReferencia()).isEqualByComparingTo("100");

			item.cambiarValor(BigDecimal.ZERO);
			assertThat(item.getValorReferencia())
					.as("cero es un valor publicado real, no una ausencia")
					.isEqualByComparingTo("0");
		}

		@Test
		@DisplayName("El codigo y el nombre son obligatorios y se recortan")
		void el_codigo_y_el_nombre_son_obligatorios() {
			assertThatThrownBy(() -> new Especialidad(1L, "  ", "N", null, AHORA, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("codigo");
			assertThatThrownBy(() -> new Especialidad(1L, "COD", null, null, AHORA, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("nombre");

			assertThat(new Especialidad(1L, "  COD  ", "  N  ", null, AHORA, null).getCodigo())
					.isEqualTo("COD");
		}
	}

	// =================================================================================
	// Resolucion del segmento de ruta
	// =================================================================================

	@Nested
	@DisplayName("Tipo de catalogo en la ruta")
	class Tipo {

		@Test
		@DisplayName("Los tres segmentos resuelven, sin distinguir mayusculas")
		void los_segmentos_resuelven() {
			assertThat(CatalogoTipo.desdeRuta("especialidades"))
					.isEqualTo(CatalogoTipo.ESPECIALIDAD);
			assertThat(CatalogoTipo.desdeRuta(" PRACTICAS "))
					.isEqualTo(CatalogoTipo.PRACTICA);
			assertThat(CatalogoTipo.desdeRuta("nomencladores"))
					.isEqualTo(CatalogoTipo.NOMENCLADOR);
			assertThat(CatalogoTipo.NOMENCLADOR.segmento()).isEqualTo("nomencladores");
		}

		@Test
		@DisplayName("Un segmento desconocido es un valor invalido del cliente, no un 404")
		void un_segmento_desconocido_se_rechaza() {
			assertThatThrownBy(() -> CatalogoTipo.desdeRuta("convenios"))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> CatalogoTipo.desdeRuta(null))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private static Especialidad especialidad(Long organizationId) {
		return new Especialidad(organizationId, "KIN", "Kinesiologia", null, AHORA, null);
	}

	private static NomencladorItem vigencia(Instant desde, Instant hasta) {
		return new NomencladorItem(
				1L, 2L, 3L, "27.01.01", "Sesion", null, new BigDecimal("10"), desde, hasta);
	}
}
