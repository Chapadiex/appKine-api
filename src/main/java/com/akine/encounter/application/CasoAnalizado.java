package com.akine.encounter.application;

import com.akine.clinical.spi.CasoDetalleSnapshot;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion;
import com.akine.encounter.domain.evolucion.DatosDelCaso;
import com.akine.organization.spi.ConsultorioSnapshot;

import java.util.List;
import java.util.Optional;

/** Lo que el analisis calculo, junto con lo que hizo falta leer para calcularlo. */
record CasoAnalizado(
		ConsultorioSnapshot sede,
		CasoDetalleSnapshot caso,
		HistoriaClinicaSnapshot historia,
		List<Sesion> cerradas,
		boolean recortada,
		Optional<PlanVigenteSnapshot> plan,
		DatosDelCaso datos,
		AnalisisDeEvolucion analisis) {
}
