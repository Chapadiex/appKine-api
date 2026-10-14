package com.akine.person.infrastructure;

import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.Vigencia;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.spi.RespaldoDocumentalDirectory.RespaldoDocumental;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** AKINE-07.04b: la evidencia documental de una prestacion ya ocurrida (RF-M21-003). */
@DisplayName("PersonRespaldoDocumentalDirectory")
class PersonRespaldoDocumentalDirectoryTest {

	private static final long ORG = 10L;
	private static final long PERSONA = 128L;
	private static final long COBERTURA = 310L;
	private static final long PRACTICA = 55L;
	private static final LocalDate DIA = LocalDate.of(2026, 8, 14);

	private final OrdenMedicaRepositoryPort ordenes = mock(OrdenMedicaRepositoryPort.class);
	private final AutorizacionRepositoryPort autorizaciones = mock(AutorizacionRepositoryPort.class);
	private final CoberturaPacienteRepositoryPort coberturas = mock(CoberturaPacienteRepositoryPort.class);
	private final PersonRespaldoDocumentalDirectory directorio =
			new PersonRespaldoDocumentalDirectory(ordenes, autorizaciones, coberturas);

	@Test
	@DisplayName("una autorizacion agotada que cubre el dia SI respalda: la sesion pudo gastar la ultima unidad")
	void autorizacionAgotadaRespalda() {
		Autorizacion agotada = autorizacion(COBERTURA, PRACTICA, new Vigencia(DIA.minusDays(10), DIA));
		given(agotada.agotada()).willReturn(true);
		given(autorizaciones.aprobadasDePersona(ORG, PERSONA)).willReturn(List.of(agotada));

		assertThat(directorio.respaldo(ORG, PERSONA, COBERTURA, PRACTICA, DIA).autorizacion()).isTrue();
	}

	@Test
	@DisplayName("una autorizacion de otra practica, otra cobertura o vencida ese dia no respalda")
	void autorizacionQueNoAmpara() {
		List<Autorizacion> ninguna = List.of(
				autorizacion(COBERTURA, 99L, new Vigencia(DIA.minusDays(10), null)),
				autorizacion(311L, PRACTICA, new Vigencia(DIA.minusDays(10), null)),
				autorizacion(COBERTURA, PRACTICA, new Vigencia(DIA.minusDays(10), DIA.minusDays(1))));
		given(autorizaciones.aprobadasDePersona(ORG, PERSONA)).willReturn(ninguna);

		assertThat(directorio.respaldo(ORG, PERSONA, COBERTURA, PRACTICA, DIA).autorizacion()).isFalse();
	}

	@Test
	@DisplayName("la orden respalda si esta vigente ese dia y sirve para la cobertura")
	void orden() {
		OrdenMedica vigente = mock(OrdenMedica.class);
		given(vigente.vigenteEl(DIA)).willReturn(true);
		given(vigente.sirveParaCobertura(COBERTURA)).willReturn(true);
		given(ordenes.activasDe(ORG, PERSONA)).willReturn(List.of(vigente));

		assertThat(directorio.respaldo(ORG, PERSONA, COBERTURA, PRACTICA, DIA).orden()).isTrue();

		given(vigente.sirveParaCobertura(COBERTURA)).willReturn(false);
		assertThat(directorio.respaldo(ORG, PERSONA, COBERTURA, PRACTICA, DIA).orden()).isFalse();
	}

	@Test
	@DisplayName("sin cobertura del tenant o sin numero de afiliado, la credencial no esta respaldada")
	void numeroDeAfiliado() {
		CoberturaPaciente cobertura = mock(CoberturaPaciente.class);
		given(cobertura.getNumeroAfiliado()).willReturn("  ");
		given(coberturas.findByIdAndOrganizationIdAndPersonaId(COBERTURA, ORG, PERSONA))
				.willReturn(Optional.of(cobertura));

		assertThat(directorio.respaldo(ORG, PERSONA, COBERTURA, PRACTICA, DIA))
				.isEqualTo(new RespaldoDocumental(false, false, false));

		given(cobertura.getNumeroAfiliado()).willReturn("123456/01");
		assertThat(directorio.respaldo(ORG, PERSONA, COBERTURA, PRACTICA, DIA).numeroAfiliado()).isTrue();
	}

	private static Autorizacion autorizacion(long coberturaId, long practicaId, Vigencia vigencia) {
		Autorizacion autorizacion = mock(Autorizacion.class);
		given(autorizacion.isActive()).willReturn(true);
		given(autorizacion.getEstado()).willReturn(EstadoAutorizacion.APROBADA);
		given(autorizacion.getCoberturaId()).willReturn(coberturaId);
		given(autorizacion.getPracticaId()).willReturn(practicaId);
		given(autorizacion.vigencia()).willReturn(vigencia);
		return autorizacion;
	}
}
