package com.akine.encounter.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.akine.clinical.spi.AccesoClinicoDirectory;
import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.PlanVigenteDirectory;
import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import com.akine.encounter.domain.evolucion.UmbralesDeEvolucion;
import com.akine.encounter.domain.exception.EvolucionDeshabilitadaException;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.MedicionDirectory;
import com.akine.scheduling.spi.TurnosDelCasoDirectory;
import org.junit.jupiter.api.Test;

import java.time.Clock;

/** RN-M31-010: con el interruptor apagado no se lee ni se audita nada. */
class EvolucionDelCasoServiceTest {

	@Test
	void apagadaRespondeNoDisponibleSinTocarNada() {
		CasoDirectory casos = mock(CasoDirectory.class);
		AccesoClinicoDirectory acceso = mock(AccesoClinicoDirectory.class);
		AuditTrail auditTrail = mock(AuditTrail.class);
		EvolucionDelCasoService service = new EvolucionDelCasoService(
				new ConfiguracionDeEvolucion(false, UmbralesDeEvolucion.porDefecto()),
				mock(ConsultorioDirectory.class), casos, mock(HistoriaClinicaDirectory.class), acceso,
				mock(PlanVigenteDirectory.class), mock(TurnosDelCasoDirectory.class),
				mock(SesionRepositoryPort.class), mock(SesionMedicionRepositoryPort.class),
				mock(TratamientoRepositoryPort.class), mock(MedicionDirectory.class), auditTrail,
				Clock.systemUTC());

		assertThatThrownBy(() -> service.analizar(new OperatingActor(1L, false, 7L, 3L), 3L, 41L, null))
				.isInstanceOf(EvolucionDeshabilitadaException.class);
		verifyNoInteractions(casos, acceso, auditTrail);
	}
}
