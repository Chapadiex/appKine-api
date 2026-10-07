package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.SesionNoCerradaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionTipo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las invariantes del historial de una sesion cerrada (AKINE-06.06) que viven en la entidad y no
 * en el servicio: por eso valen para cualquier camino futuro que no pase por
 * {@code SesionService}.
 */
@DisplayName("SesionVersion")
class SesionVersionTest {

	private static final Instant CIERRE = Instant.parse("2026-09-15T12:48:00Z");

	private static final FotoClinica SIN_FOTO = new FotoClinica(null, null);

	@Test
	@DisplayName("Una sesion abierta no tiene version 1: el historial lo inaugura el cierre")
	void la_abierta_no_tiene_original() {
		assertThatThrownBy(() -> SesionVersion.original(sesion(null), SIN_FOTO))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("La version 1 lleva el instante y el autor DEL CIERRE, y ningun motivo")
	void el_original_es_el_cierre() {
		Sesion sesion = cerrada(null);

		SesionVersion original = SesionVersion.original(sesion, SIN_FOTO);

		assertThat(original.getNumeroVersion()).isEqualTo(1);
		assertThat(original.esEnmienda()).isFalse();
		assertThat(original.getMotivoEnmienda()).isNull();
		assertThat(original.getRegistradaEn()).isEqualTo(CIERRE);
		assertThat(original.getRegistradaPor()).isEqualTo(99L);
		assertThat(original.getNotaDeCierre()).isEqualTo("Terapia manual");
	}

	@Test
	@DisplayName("C-6: la version COPIA tratamientos, parametros y mediciones: corregir los vivos "
			+ "despues no cambia lo que dice")
	void la_foto_es_una_copia() {
		Sesion sesion = cerrada(null);
		TratamientoRealizado tratamiento = new TratamientoRealizado(1L, 7L, 1L, 1, 41L, "US-01",
				"Ultrasonido", 31L, CIERRE, 99L);
		ReflectionTestUtils.setField(tratamiento, "id", 300L);
		tratamiento.redefinir(41L, "US-01", "Ultrasonido", null, "Rodilla", Lateralidad.DERECHA,
				15, 31L, null, null, null, CIERRE);
		TratamientoParametro intensidad = new TratamientoParametro(1L, 300L, new ParametroAplicado(
				"intensidad", TipoDatoParametro.NUMERICO, new BigDecimal("2.5"), null, null, "mA"),
				0, CIERRE);
		SesionMedicion rom = new SesionMedicion(1L, 1L, new MedicionDefinicionSnapshot(61L, 1L,
				"ROM", "Rango", MedicionTipo.NUMERICO, "grados", BigDecimal.ZERO,
				new BigDecimal("180"), true, 3L), LateralidadMedicion.DERECHA,
				new ValorMedido(new BigDecimal("90"), null, null), null, CIERRE, 99L);

		SesionVersion original = SesionVersion.original(sesion, new FotoClinica(
				List.of(new FotoClinica.Tratamiento(tratamiento, List.of(intensidad))),
				List.of(rom)));

		tratamiento.redefinir(41L, "US-01", "Ultrasonido", null, "Hombro", Lateralidad.IZQUIERDA,
				15, 31L, null, null, null, CIERRE);
		rom.actualizar(new ValorMedido(new BigDecimal("120"), null, null), null, CIERRE, 8L);

		assertThat(original.getTratamientos()).singleElement().satisfies(foto -> {
			assertThat(foto.getTratamientoRealizadoId()).isEqualTo(300L);
			assertThat(foto.getZona()).isEqualTo("Rodilla");
			assertThat(foto.getLateralidad()).isEqualTo(Lateralidad.DERECHA);
			assertThat(foto.getParametros()).singleElement()
					.satisfies(p -> assertThat(p.getValorNumerico()).isEqualByComparingTo("2.5"));
		});
		assertThat(original.getMediciones()).singleElement().satisfies(foto -> {
			assertThat(foto.getValorNumerico()).isEqualByComparingTo("90");
			assertThat(foto.getRegistradaPorCuentaId()).isEqualTo(99L);
		});
	}

	@Test
	@DisplayName("C-6: una version sin foto no se puede escribir")
	void la_version_exige_foto() {
		assertThatThrownBy(() -> SesionVersion.original(cerrada(null), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("El motivo de la enmienda se guarda sin espacios de borde")
	void el_motivo_se_normaliza() {
		Sesion sesion = cerrada(null);
		sesion.enmendar(contenido("Nota corregida"), Asistencia.PRESENTE);

		SesionVersion enmienda = SesionVersion.enmienda(
				sesion, SIN_FOTO, "  Lateralidad corregida  ", CIERRE.plusSeconds(60), 8L);

		assertThat(enmienda.getNumeroVersion()).isEqualTo(2);
		assertThat(enmienda.esEnmienda()).isTrue();
		assertThat(enmienda.getMotivoEnmienda()).isEqualTo("Lateralidad corregida");
		assertThat(enmienda.getRegistradaPor()).as("el autor es por version").isEqualTo(8L);
	}

	@Test
	@DisplayName("Un motivo mas largo que la columna se rechaza en la entidad, no truncado por MySQL")
	void el_motivo_largo_se_rechaza() {
		// Del otro lado no hay un 400 prolijo: hay un VARCHAR truncado que el handler global
		// devuelve como 409 "choca con un dato existente", y la enmienda se pierde.
		Sesion sesion = cerrada(null);
		sesion.enmendar(contenido("Nota corregida"), Asistencia.PRESENTE);

		assertThatThrownBy(() -> SesionVersion.enmienda(
				sesion, SIN_FOTO, "x".repeat(SesionVersion.MOTIVO_MAXIMO + 1), CIERRE, 8L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Una version sin autor no se puede escribir: el historial dice siempre quien")
	void la_version_exige_autor() {
		Sesion sesion = cerrada(null);
		sesion.enmendar(contenido("Nota corregida"), Asistencia.PRESENTE);

		assertThatThrownBy(() -> SesionVersion.enmienda(sesion, SIN_FOTO, "motivo", CIERRE, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Una sesion abierta no se enmienda: se guarda")
	void la_abierta_no_se_enmienda() {
		assertThatThrownBy(() -> sesion(null).enmendar(contenido("Nota"), Asistencia.PRESENTE))
				.isInstanceOf(SesionNoCerradaException.class);
	}

	@Test
	@DisplayName("Una sesion sin caso no recibe numero de caso al cerrar")
	void sin_caso_no_hay_numero_de_caso() {
		Sesion sesion = sesion(null);
		CierreDeSesion cierre =
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null);

		assertThatThrownBy(() -> sesion.cerrar(cierre, 8, 3, CIERRE, 99L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(sesion.estaCerrada()).isFalse();
	}

	private static Sesion sesion(Long casoId) {
		Sesion sesion = new Sesion(1L, 7L, 88L, casoId, 301L, 42L, 31L, Instant.EPOCH, 99L);
		ReflectionTestUtils.setField(sesion, "id", 1L);
		return sesion;
	}

	private static Sesion cerrada(Long casoId) {
		Sesion sesion = sesion(casoId);
		sesion.cerrar(new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null,
				null), 8, null, CIERRE, 99L);
		return sesion;
	}

	private static ContenidoDeSesion contenido(String notaDeCierre) {
		return new ContenidoDeSesion("dolor lumbar", 4, "Lumbar", Lateralidad.DERECHA,
				Evolucion.MEJOR, null, null, notaDeCierre, null, null, null, null);
	}
}
