package com.akine.person.api.dto;

import com.akine.contracting.spi.ArancelVigente;
import com.akine.person.application.CoberturaParaOferta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Que cubre la obra social de la persona para una oferta en una fecha (B-3, RF-M08-006/007). */
@Schema(description = "Cobertura aplicable por oferta y condicion particular sugerida")
public record CoberturaParaOfertaResponse(

		@Schema(description = "Persona", example = "1204")
		long personaId,

		@Schema(description = "Oferta", example = "34")
		long ofertaId,

		@Schema(description = "Dia contra el que se resolvio", example = "2026-11-03")
		LocalDate fecha,

		@Schema(description = "Si la oferta admite obra social. En false toda cobertura sale en "
				+ "noAplicables con OFERTA_NO_ADMITE_OBRA_SOCIAL")
		boolean admiteObraSocial,

		@Schema(description = "COBERTURA si al menos una cobertura aplica, PARTICULAR si ninguna. Es "
				+ "una sugerencia: atender como particular igual es decision del operador y no toca "
				+ "la cobertura del paciente", allowableValues = {"COBERTURA", "PARTICULAR"})
		String condicionSugerida,

		@Schema(description = "Precio particular que rige ese dia (precio por vigencia o de lista). "
				+ "Null si la oferta no esta tarifada", example = "8500.00")
		BigDecimal precioParticular,

		@Schema(description = "ISO 4217 del precio particular", example = "ARS")
		String moneda,

		@Schema(description = "Coberturas que aplican. La principal primero")
		List<Aplicable> aplicables,

		@Schema(description = "Coberturas vigentes que NO aplican, con motivo")
		List<NoAplicable> noAplicables) {

	@Schema(name = "CoberturaParaOfertaArancel",
			description = "Arancel de una practica de la oferta bajo la cobertura")
	public record Arancel(
			@Schema(description = "Convenio del que salio", example = "140") long convenioId,
			@Schema(description = "Codigo del convenio", example = "OSDE-2026") String convenioCodigo,
			@Schema(description = "Arancel", example = "901") long arancelId,
			@Schema(description = "Null si es el arancel general de la practica; el id de la oferta "
					+ "si es el arancel especifico de la oferta (RF-M16-008)", example = "34")
			Long ofertaId,
			@Schema(example = "12000.00") BigDecimal importeTotal,
			@Schema(example = "9600.00") BigDecimal importeFinanciador,
			@Schema(example = "2400.00") BigDecimal coseguro,
			@Schema(example = "ARS") String moneda,
			boolean requiereOrden,
			boolean requiereAutorizacion,
			boolean requiereCredencial) {

		static Arancel de(ArancelVigente a) {
			return a == null ? null : new Arancel(a.convenioId(), a.convenioCodigo(), a.arancelId(),
					a.ofertaId(), a.importeTotal(), a.importeFinanciador(), a.coseguro(), a.moneda(),
					a.requiereOrden(), a.requiereAutorizacion(), a.requiereCredencial());
		}
	}

	@Schema(name = "CoberturaParaOfertaPractica",
			description = "Como resolvio una practica de la oferta bajo la cobertura")
	public record Practica(
			@Schema(example = "412") long practicaId,
			@Schema(description = "Si es la practica principal de la oferta") boolean principal,
			@Schema(description = "Null si no resolvio") Arancel arancel,
			@Schema(description = "Por que no resolvio. Null si resolvio",
					allowableValues = {"SIN_CONVENIO_VIGENTE", "SIN_ARANCEL_VIGENTE"})
			String motivo) {

		static Practica de(CoberturaParaOferta.Practica p) {
			return new Practica(p.practicaId(), p.principal(), Arancel.de(p.arancel()),
					p.motivo() == null ? null : p.motivo().name());
		}
	}

	@Schema(name = "CoberturaParaOfertaAplicable", description = "Una cobertura que aplica a la oferta")
	public record Aplicable(
			@Schema(example = "77") long coberturaId,
			boolean principal,
			@Schema(example = "31") long financiadorId,
			@Schema(example = "OSDE") String financiadorNombre,
			@Schema(example = "88") long planId,
			@Schema(example = "210") String planNombre,
			@Schema(description = "Primera practica de la oferta que resolvio, la principal primero",
					example = "412") long practicaId,
			Arancel arancel,
			@Schema(description = "Solo alerta: no excluye la cobertura") boolean credencialVencida,
			LocalDate credencialVigenciaHasta,
			@Schema(description = "Detalle por practica de la oferta") List<Practica> practicas) {
	}

	@Schema(name = "CoberturaParaOfertaNoAplicable",
			description = "Una cobertura vigente que no aplica a la oferta")
	public record NoAplicable(
			@Schema(example = "78") long coberturaId,
			boolean principal,
			@Schema(example = "32") long financiadorId,
			@Schema(example = "Swiss Medical") String financiadorNombre,
			@Schema(example = "90") long planId,
			@Schema(example = "SMG20") String planNombre,
			@Schema(allowableValues = {"OFERTA_NO_ADMITE_OBRA_SOCIAL", "OFERTA_SIN_PRACTICAS",
					"SIN_CONVENIO_VIGENTE", "SIN_ARANCEL_VIGENTE"})
			String motivo,
			@Schema(description = "Detalle por practica. Vacio si el corte fue la oferta")
			List<Practica> practicas) {
	}

	public static CoberturaParaOfertaResponse de(CoberturaParaOferta r) {
		return new CoberturaParaOfertaResponse(
				r.personaId(), r.ofertaId(), r.fecha(), r.admiteObraSocial(),
				r.condicionSugerida().name(), r.precioParticular(), r.moneda(),
				r.aplicables().stream().map(a -> new Aplicable(
						a.coberturaId(), a.principal(),
						a.referencia().financiadorId(), a.referencia().financiadorNombre(),
						a.referencia().planId(), a.referencia().planNombre(),
						a.practicaId(), Arancel.de(a.arancel()), a.credencialVencida(),
						a.credencialVigenciaHasta(),
						a.practicas().stream().map(Practica::de).toList())).toList(),
				r.noAplicables().stream().map(n -> new NoAplicable(
						n.coberturaId(), n.principal(),
						n.referencia().financiadorId(), n.referencia().financiadorNombre(),
						n.referencia().planId(), n.referencia().planNombre(),
						n.motivo().name(),
						n.practicas().stream().map(Practica::de).toList())).toList());
	}
}
