package com.akine.encounter.api;

import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.CierreIncompletoException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.EnmiendaSinMotivoException;
import com.akine.encounter.domain.exception.EspacioNoAccesibleException;
import com.akine.encounter.domain.exception.EspacioNoOperableException;
import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;
import com.akine.encounter.domain.exception.MedicionDefinicionInactivaException;
import com.akine.encounter.domain.exception.MedicionDefinicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionFueraDeRangoException;
import com.akine.encounter.domain.exception.MedicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionTipoIncompatibleException;
import com.akine.encounter.domain.exception.ParametroInvalidoException;
import com.akine.encounter.domain.exception.PracticaNoUtilizableException;
import com.akine.encounter.domain.exception.ProfesionalNoAsignableException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNoCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TratamientoNoAccesibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de errores de {@code encounter} (M14).
 *
 * <h2>La distincion que esta clase existe para sostener</h2>
 *
 * <p><b>La propiedad no es un permiso.</b> Escribir en la atencion ajena devuelve <b>409</b> y no
 * 403, porque quien opera SI tiene {@code sesion:register}: un 403 mandaria a la pantalla a decir
 * "no tenes permiso", que es falso, y a pedir un permiso que ya tiene. Lo mismo con el co-atendiente
 * que no esta vinculado a la sede: el permiso esta bien, lo que no sirve es el dato declarado.
 *
 * <p><b>Y el reparto 400 contra 409 no es estetico:</b> un valor fuera de rango o un parametro sin
 * tipo no dependen de nada que pueda cambiar entre dos peticiones, asi que un 409 —que sugiere
 * reintentar— mandaria al cliente a repetir algo que va a fallar igual.
 */
@DisplayName("EncounterProblemHandler")
class EncounterProblemHandlerTest {

	private final EncounterProblemHandler handler = new EncounterProblemHandler();

	@Nested
	@DisplayName("Fuera del alcance")
	class FueraDelAlcance {

		@Test
		@DisplayName("Sede, sesion, espacio, tratamiento, medicion y su definicion: todos 404")
		void lo_inalcanzable_es_404() {
			List<ProblemDetail> respuestas = List.of(
					handler.handleConsultorioNoAccesible(new ConsultorioNoAccesibleException(7L)),
					handler.handleSesionNoAccesible(new SesionNotAccessibleException(500L)),
					handler.handleEspacioNoAccesible(new EspacioNoAccesibleException(12L)),
					handler.handleTratamientoNoAccesible(new TratamientoNoAccesibleException(900L)),
					handler.handleMedicionNoAccesible(
							new MedicionNoAccesibleException(61L, "DERECHA")));

			assertThat(respuestas).allSatisfy(problem -> {
				assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
				assertThat(problem.getTitle()).isNotBlank();
			});
		}

		@Test
		@DisplayName("La definicion de medicion lleva type PROPIO: lo emiten dos modulos")
		void definicion_de_medicion() {
			// `resource` lo emite desde la administracion del catalogo y `encounter` desde el
			// registro de una medicion. Un solo type para los dos, porque para el cliente la
			// situacion es la misma.
			ProblemDetail problem = handler.handleMedicionDefinicionNoAccesible(
					new MedicionDefinicionNoAccesibleException(61L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
			assertThat(problem.getType())
					.isEqualTo(ProblemType.MEDICION_DEFINICION_NO_ACCESIBLE.uri());
		}
	}

	@Nested
	@DisplayName("La propiedad de la atencion")
	class Propiedad {

		@Test
		@DisplayName("La sesion ajena es 409 y NO 403")
		void sesion_ajena_es_409() {
			ProblemDetail problem = handler.handleSesionAjena(new SesionAjenaException(500L, 31L));

			assertThat(problem.getStatus())
					.as("quien opera tiene el permiso: lo que no es suyo es la atencion")
					.isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.SESION_AJENA.uri());
		}

		@Test
		@DisplayName("El co-atendiente no vinculado tambien es 409, por lo mismo")
		void profesional_no_asignable() {
			ProblemDetail problem = handler.handleProfesionalNoAsignable(
					new ProfesionalNoAsignableException(32L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.PROFESIONAL_NO_ASIGNABLE.uri());
		}
	}

	@Nested
	@DisplayName("Lo que depende del cuerpo enviado: 400")
	class DelCuerpo {

		@Test
		@DisplayName("Un valor fuera de rango viaja con el minimo, el maximo y el valor")
		void fuera_de_rango() {
			// Un "fuera de rango" sin numeros es inaccionable: la pantalla tiene que poder decir
			// entre que y que.
			ProblemDetail problem = handler.handleMedicionFueraDeRango(
					new MedicionFueraDeRangoException(
							"EVA", new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.TEN));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.MEDICION_FUERA_DE_RANGO.uri());
			assertThat(problem.getProperties()).containsKeys("minimo", "maximo", "valor");
		}

		@Test
		@DisplayName("El tipo incompatible lleva el tipo esperado y el motivo")
		void tipo_incompatible() {
			// Las dos mitades importan: que falte el valor deja una medicion que no mide nada, y
			// que sobre otro es el principio de una medicion que despues nadie puede comparar.
			ProblemDetail problem = handler.handleMedicionTipoIncompatible(
					new MedicionTipoIncompatibleException("EVA", "NUMERICO", "llego texto"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
			assertThat(problem.getProperties()).containsKeys("tipoEsperado", "motivo");
		}

		@Test
		@DisplayName("El parametro invalido dice CUAL de los parametros esta mal")
		void parametro_invalido() {
			// Un 400 que no dice cual de los seis parametros falla obliga al usuario a probar de a
			// uno.
			ProblemDetail problem = handler.handleParametroInvalido(
					new ParametroInvalidoException("intensidad", "falta el tipo de dato"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
			assertThat(problem.getProperties()).containsKey("clave");
		}

		@Test
		@DisplayName("La evaluacion incoherente, el cierre incompleto y la enmienda sin motivo son 400")
		void las_otras_tres_del_cuerpo() {
			// Todo lo clinico es nullable —seguimiento no exige examen completo— y por eso se
			// valida lo que seria FALSO, no lo que falta.
			List<ProblemDetail> respuestas = List.of(
					handler.handleEvaluacionIncoherente(
							new EvaluacionIncoherenteException("dolor sin zona")),
					handler.handleCierreIncompleto(
							new CierreIncompletoException("falta la evolucion")),
					handler.handleEnmiendaSinMotivo(new EnmiendaSinMotivoException(500L)));

			assertThat(respuestas).allSatisfy(problem ->
					assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value()));
		}
	}

	@Nested
	@DisplayName("Lo que depende del estado: 409")
	class DelEstado {

		@Test
		@DisplayName("Una sesion cerrada no se edita: se enmienda, y eso es otra operacion")
		void sesion_cerrada() {
			ProblemDetail problem = handler.handleSesionCerrada(new SesionCerradaException(500L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.SESION_CERRADA.uri());
		}

		@Test
		@DisplayName("Enmendar una sesion que NO esta cerrada tambien es 409, y es su opuesto")
		void sesion_no_cerrada() {
			ProblemDetail problem = handler.handleSesionNoCerrada(
					new SesionNoCerradaException(500L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.SESION_NO_CERRADA.uri());
		}

		@Test
		@DisplayName("Una definicion dada de baja es 409: existe, se ve, y corresponde elegir otra")
		void definicion_inactiva() {
			// Hace visible que la baja NO cascadea: lo ya registrado sigue legible y comparable.
			ProblemDetail problem = handler.handleMedicionDefinicionInactiva(
					new MedicionDefinicionInactivaException(61L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.MEDICION_DEFINICION_INACTIVA.uri());
		}

		@Test
		@DisplayName("Un espacio fuera de servicio es 409 y uno de otra sede es 404")
		void espacio_segun_el_motivo() {
			assertThat(handler.handleEspacioNoOperable(new EspacioNoOperableException(12L))
					.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(handler.handleEspacioNoAccesible(new EspacioNoAccesibleException(12L))
					.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		}

		@Test
		@DisplayName("El turno no atendible lleva el motivo")
		void turno_no_atendible() {
			ProblemDetail problem = handler.handleTurnoNoAtendible(
					new TurnoNoAtendibleException(301L, "esta cancelado"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.TURNO_NO_ATENDIBLE.uri());
		}
	}

	@Nested
	@DisplayName("Los dos que cambian de status segun el motivo")
	class SegunElMotivo {

		@Test
		@DisplayName("La practica inexistente es 404 y la no vigente es 409")
		void practica_segun_el_motivo() {
			// La diferencia importa porque lleva a otra accion: la no vigente existe y es visible,
			// asi que lo que corresponde es elegir otra, no buscar el id.
			assertThat(handler.handlePracticaNoUtilizable(new PracticaNoUtilizableException(
					77L, PracticaNoUtilizableException.Motivo.INEXISTENTE)).getStatus())
					.isEqualTo(HttpStatus.NOT_FOUND.value());
			assertThat(handler.handlePracticaNoUtilizable(new PracticaNoUtilizableException(
					77L, PracticaNoUtilizableException.Motivo.NO_VIGENTE)).getStatus())
					.isEqualTo(HttpStatus.CONFLICT.value());
		}

		@Test
		@DisplayName("El caso no accesible es 404, el de otra historia tambien, y el cerrado es 409")
		void caso_segun_el_motivo() {
			// "De otra historia" NO se distingue de "no existe": distinguirlos permitiria averiguar
			// que casos tiene abiertos otro paciente.
			assertThat(handler.handleCasoNoAsignable(new CasoNoAsignableException(
					55L, CasoNoAsignableException.Motivo.NO_ACCESIBLE)).getStatus())
					.isEqualTo(HttpStatus.NOT_FOUND.value());
			assertThat(handler.handleCasoNoAsignable(new CasoNoAsignableException(
					55L, CasoNoAsignableException.Motivo.DE_OTRA_HISTORIA)).getStatus())
					.isEqualTo(HttpStatus.NOT_FOUND.value());
			assertThat(handler.handleCasoNoAsignable(new CasoNoAsignableException(
					55L, CasoNoAsignableException.Motivo.CERRADO)).getStatus())
					.isEqualTo(HttpStatus.CONFLICT.value());
		}
	}
}
