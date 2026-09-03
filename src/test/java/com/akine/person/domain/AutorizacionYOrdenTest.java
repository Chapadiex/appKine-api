package com.akine.person.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.akine.contracting.spi.ArancelCongelado;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las invariantes que el dominio de M17 hace cumplir sin base de datos.
 *
 * <p>Lo que estos casos fijan: que VENCIDA y AGOTADA se calculen y no se guarden, que la copia del
 * convenio viaje entera o no viaje, que una orden no pueda valer antes de escribirse, y que las
 * transiciones terminales lo sean de verdad.
 */
@DisplayName("Dominio de ordenes y autorizaciones (M17)")
class AutorizacionYOrdenTest {

	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate JUNIO = LocalDate.of(2027, 6, 30);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);

	@Nested
	@DisplayName("Vigencia")
	class DeLaVigencia {

		@Test
		@DisplayName("vigencia_hasta es INCLUSIVA: el ultimo dia todavia cuenta")
		void hasta_inclusiva() {
			// Lo fijo 03.03 para los planes y lo repitieron V42 y V43. Cambiarlo aca dejaria a la
			// orden contando un dia menos que la cobertura que la respalda.
			assertThat(new Vigencia(ENERO, JUNIO).cubre(JUNIO)).isTrue();
			assertThat(new Vigencia(ENERO, JUNIO).cubre(JUNIO.plusDays(1))).isFalse();
		}

		@Test
		@DisplayName("sin fin previsto cubre cualquier fecha posterior al inicio")
		void sin_fin() {
			assertThat(new Vigencia(ENERO, null).cubre(LocalDate.of(2099, 1, 1))).isTrue();
			assertThat(new Vigencia(ENERO, null).cubre(ENERO.minusDays(1))).isFalse();
		}

		@Test
		@DisplayName("el solapamiento que ningun unique expresa: enero-junio contra marzo-diciembre")
		void solapamiento() {
			// No comparten un solo valor de columna, y MySQL 8.4 no tiene exclusion constraints.
			assertThat(new Vigencia(ENERO, JUNIO)
					.seSolapaCon(new Vigencia(LocalDate.of(2027, 3, 1), DICIEMBRE))).isTrue();
		}

		@Test
		@DisplayName("dos periodos CONSECUTIVOS no se solapan: renovar es el caso normal")
		void consecutivos_no_se_solapan() {
			assertThat(new Vigencia(ENERO, JUNIO)
					.seSolapaCon(new Vigencia(JUNIO.plusDays(1), DICIEMBRE))).isFalse();
		}

		@Test
		@DisplayName("una vigencia invertida no se puede construir")
		void invertida() {
			assertThatThrownBy(() -> new Vigencia(JUNIO, ENERO))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("diasHasta es la alerta de vencimiento, y es negativa cuando ya vencio")
		void dias_hasta() {
			assertThat(new Vigencia(ENERO, JUNIO).diasHasta(JUNIO.minusDays(10))).isEqualTo(10L);
			assertThat(new Vigencia(ENERO, JUNIO).diasHasta(JUNIO.plusDays(5))).isEqualTo(-5L);
			assertThat(new Vigencia(ENERO, null).diasHasta(JUNIO)).isNull();
		}
	}

	@Nested
	@DisplayName("Orden medica")
	class DeLaOrden {

		@Test
		@DisplayName("una orden no puede empezar a valer antes de su fecha de emision")
		void emision_previa() {
			assertThatThrownBy(() -> orden(JUNIO, ENERO, DICIEMBRE))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("antes de su fecha de emision");
		}

		@Test
		@DisplayName("sin vigenciaDesde se toma la fecha de emision, que es el unico inicio que no puede estar mal")
		void vigencia_por_defecto() {
			assertThat(orden(ENERO, null, DICIEMBRE).getVigenciaDesde()).isEqualTo(ENERO);
		}

		@Test
		@DisplayName("editar solo la fecha de emision revalida la coherencia temporal")
		void la_edicion_revalida() {
			// El descuido con el que se rompe la invariante sin darse cuenta: mover la emision
			// hacia adelante puede dejarla despues del inicio de vigencia.
			OrdenMedica orden = orden(ENERO, ENERO, DICIEMBRE);

			assertThatThrownBy(() -> orden.updateDatos(
					null, null, null, JUNIO, null, null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("vencida NO es dada de baja: la orden sigue siendo operable")
		void vencida_no_es_baja() {
			OrdenMedica orden = orden(ENERO, ENERO, JUNIO);

			assertThat(orden.vigenteEl(DICIEMBRE)).isFalse();
			assertThat(orden.isOperable()).isTrue();
			assertThat(orden.diasParaVencer(DICIEMBRE)).isNegative();
		}

		@Test
		@DisplayName("una orden sin cobertura sirve para cualquiera; una atada, solo para la suya")
		void sirve_para_cobertura() {
			assertThat(orden(ENERO, ENERO, null).sirveParaCobertura(412L)).isTrue();

			OrdenMedica atada = new OrdenMedica(7L, 1204L, 20L, 412L, null, "Dra. Sintetica", null,
					ENERO, null, null, ENERO, null, null);
			assertThat(atada.sirveParaCobertura(412L)).isTrue();
			assertThat(atada.sirveParaCobertura(999L)).isFalse();
		}

		@Test
		@DisplayName("cero sesiones prescriptas no es 'sin cantidad': se rechaza")
		void cero_sesiones() {
			assertThatThrownBy(() -> new OrdenMedica(7L, 1204L, 20L, null, null, "Dra. Sintetica",
					null, ENERO, null, 0, ENERO, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("la orden necesita un profesional que la firme")
		void emisor_obligatorio() {
			assertThatThrownBy(() -> new OrdenMedica(7L, 1204L, 20L, null, null, "  ", null,
					ENERO, null, null, ENERO, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("vincular y desvincular el documento no toca nada mas")
		void documento() {
			OrdenMedica orden = orden(ENERO, ENERO, null);
			orden.vincularDocumento(907L);
			assertThat(orden.getAdjuntoId()).isEqualTo(907L);
			orden.desvincularDocumento();
			assertThat(orden.getAdjuntoId()).isNull();
		}

		private OrdenMedica orden(LocalDate emision, LocalDate desde, LocalDate hasta) {
			return new OrdenMedica(7L, 1204L, 20L, null, "OM-1", "Dra. Sintetica", "MP 1",
					emision, "Kinesiologia", 10, desde, hasta, null);
		}
	}

	@Nested
	@DisplayName("Autorizacion")
	class DeLaAutorizacion {

		@Test
		@DisplayName("autorizado y consumido son dos numeros: el saldo es la resta, y hoy vale lo autorizado")
		void saldo() {
			Autorizacion autorizacion = autorizacion(EstadoAutorizacion.APROBADA, 10, DICIEMBRE);

			// RN-M17-001. cantidadConsumida nace en cero y esta etapa no la mueve: el saldo que se
			// devuelve es el inicial, y eso hay que saberlo antes de creer que la resta ya corre.
			assertThat(autorizacion.getCantidadConsumida()).isZero();
			assertThat(autorizacion.saldo()).isEqualTo(10);
			assertThat(autorizacion.agotada()).isFalse();
		}

		@Test
		@DisplayName("sin tope declarado el saldo es null y la autorizacion nunca esta agotada")
		void sin_tope() {
			Autorizacion autorizacion = autorizacion(EstadoAutorizacion.APROBADA, null, DICIEMBRE);

			assertThat(autorizacion.saldo()).isNull();
			assertThat(autorizacion.agotada()).isFalse();
			assertThat(autorizacion.habilitaEl(JUNIO)).isTrue();
		}

		@Test
		@DisplayName("habilita exige las cuatro condiciones: activa, APROBADA, vigente y con saldo")
		void habilita() {
			assertThat(autorizacion(EstadoAutorizacion.PENDIENTE, 10, DICIEMBRE).habilitaEl(JUNIO))
					.as("una PENDIENTE no habilita")
					.isFalse();
			assertThat(autorizacion(EstadoAutorizacion.APROBADA, 10, JUNIO)
					.habilitaEl(DICIEMBRE))
					.as("una vencida no habilita, y sigue existiendo")
					.isFalse();

			Autorizacion agotada = autorizacion(EstadoAutorizacion.APROBADA, 3, DICIEMBRE);
			ReflectionTestUtils.setField(agotada, "cantidadConsumida", 3);
			assertThat(agotada.habilitaEl(JUNIO)).as("una agotada no habilita").isFalse();

			Autorizacion baja = autorizacion(EstadoAutorizacion.APROBADA, 10, DICIEMBRE);
			baja.deactivate(Instant.now(), "cargada por error");
			assertThat(baja.habilitaEl(JUNIO)).as("una dada de baja no habilita").isFalse();
		}

		@Test
		@DisplayName("vencida y agotada NO son estados guardados: el estado sigue siendo APROBADA")
		void vencida_no_es_un_estado() {
			Autorizacion autorizacion = autorizacion(EstadoAutorizacion.APROBADA, 10, JUNIO);

			// Materializarlas exigiria un job, y un job que no corre deja autorizaciones vencidas
			// que el sistema cree vigentes.
			assertThat(autorizacion.vencidaEl(DICIEMBRE)).isTrue();
			assertThat(autorizacion.getEstado()).isEqualTo(EstadoAutorizacion.APROBADA);
		}

		@Test
		@DisplayName("una autorizacion no se puede cargar OBSERVADA ni RECHAZADA")
		void estado_inicial_acotado() {
			assertThatThrownBy(() -> autorizacion(EstadoAutorizacion.OBSERVADA, 10, DICIEMBRE))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> autorizacion(EstadoAutorizacion.RECHAZADA, 10, DICIEMBRE))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("APROBADA y RECHAZADA son terminales; PENDIENTE y OBSERVADA admiten resolucion")
		void transiciones() {
			assertThat(EstadoAutorizacion.PENDIENTE.admiteResolucion()).isTrue();
			assertThat(EstadoAutorizacion.OBSERVADA.admiteResolucion()).isTrue();
			assertThat(EstadoAutorizacion.APROBADA.admiteResolucion()).isFalse();
			assertThat(EstadoAutorizacion.RECHAZADA.admiteResolucion()).isFalse();
		}

		@Test
		@DisplayName("aprobar con menos cantidad y menos ventana ES la autorizacion parcial")
		void autorizacion_parcial() {
			Autorizacion autorizacion = autorizacion(EstadoAutorizacion.PENDIENTE, 20, DICIEMBRE);

			autorizacion.resolver(AccionSobreAutorizacion.APROBAR, null, 6, null, JUNIO);

			// Lo que vale es lo que el financiador concedio, no lo que el centro pidio.
			assertThat(autorizacion.getEstado()).isEqualTo(EstadoAutorizacion.APROBADA);
			assertThat(autorizacion.getCantidadAutorizada()).isEqualTo(6);
			assertThat(autorizacion.getVigenciaHasta()).isEqualTo(JUNIO);
		}

		@Test
		@DisplayName("observar y rechazar exigen motivo; aprobar no")
		void motivo_exigido() {
			assertThat(AccionSobreAutorizacion.APROBAR.exigeMotivo()).isFalse();
			assertThat(AccionSobreAutorizacion.OBSERVAR.exigeMotivo()).isTrue();
			assertThat(AccionSobreAutorizacion.RECHAZAR.exigeMotivo()).isTrue();

			Autorizacion autorizacion = autorizacion(EstadoAutorizacion.PENDIENTE, 10, DICIEMBRE);
			assertThatThrownBy(() -> autorizacion.resolver(
					AccionSobreAutorizacion.RECHAZAR, "  ", null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("la copia del convenio viaja ENTERA o no viaja")
		void snapshot_entero() {
			Autorizacion sinConvenio = autorizacion(EstadoAutorizacion.PENDIENTE, 10, DICIEMBRE);
			assertThat(sinConvenio.tieneConvenioCongelado()).isFalse();
			assertThat(sinConvenio.getConvenioCodigo()).isNull();
			assertThat(sinConvenio.getRequeriaOrden()).isNull();

			Autorizacion conConvenio = new Autorizacion(
					7L, 1204L, 20L, 412L, null, 33L, "AUT-2", EstadoAutorizacion.PENDIENTE, 10,
					ENERO, DICIEMBRE, congelado(), null);

			assertThat(conConvenio.tieneConvenioCongelado()).isTrue();
			assertThat(conConvenio.getConvenioCodigo()).isEqualTo("CONV-1");
			assertThat(conConvenio.getRequeriaOrden()).isTrue();
			assertThat(conConvenio.getRequeriaAutorizacion()).isTrue();
			assertThat(conConvenio.getRequeriaCredencial()).isFalse();
			assertThat(conConvenio.getReferenciaCapturadaEl()).isNotNull();
		}

		@Test
		@DisplayName("recortar la cantidad por debajo de lo ya consumido se rechaza con un mensaje que lo explica")
		void no_por_debajo_del_consumo() {
			// Hoy el consumo es siempre cero y esto no puede dispararse. Esta escrito igual porque
			// el dia que RF-M17-004 lo mueva, el saldo negativo moriria en un CHECK sin explicar.
			Autorizacion autorizacion = autorizacion(EstadoAutorizacion.PENDIENTE, 10, DICIEMBRE);
			ReflectionTestUtils.setField(autorizacion, "cantidadConsumida", 4);

			assertThatThrownBy(() -> autorizacion.updateDatos(null, null, 2, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("ya consumidas");
		}

		@Test
		@DisplayName("el numero de autorizacion es obligatorio: sin el no se puede presentar la liquidacion")
		void numero_obligatorio() {
			assertThatThrownBy(() -> new Autorizacion(
					7L, 1204L, 20L, 412L, null, 33L, "  ", EstadoAutorizacion.PENDIENTE, 10,
					ENERO, DICIEMBRE, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("cero sesiones autorizadas no es 'sin tope': se rechaza")
		void cero_no_es_sin_tope() {
			assertThatThrownBy(() -> autorizacion(EstadoAutorizacion.PENDIENTE, 0, DICIEMBRE))
					.isInstanceOf(IllegalArgumentException.class);
		}

		private Autorizacion autorizacion(
				EstadoAutorizacion estado, Integer cantidad, LocalDate hasta) {

			return new Autorizacion(7L, 1204L, 20L, 412L, null, 33L, "AUT-1", estado, cantidad,
					ENERO, hasta, null, null);
		}

		private ArancelCongelado congelado() {
			return new ArancelCongelado(
					12L, "CONV-1", "Convenio Sintetico", "PRESTACION", 88L, 99L, 33L, 5L,
					new BigDecimal("12000.00"), new BigDecimal("10000.00"),
					new BigDecimal("2000.00"), "ARS", true, true, false, ENERO, Instant.now());
		}
	}
}
