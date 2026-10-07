package com.akine.person.infrastructure;

import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.spi.ConsumoDeAutorizaciones;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publica el consumo de autorizaciones hacia los demas modulos (RF-M17-004).
 *
 * <p>Adaptador delgado, mismo patron y mismo motivo que {@code PersonPacienteDirectory}: el
 * {@code spi} es lo unico publico del modulo y la clase que lo implementa no agrega logica, solo
 * traduce. Tener el adaptador aunque no haga nada es lo que permite cambiar la forma interna del
 * servicio sin tocar el contrato que otros modulos compilan contra el.
 *
 * <p>La transaccion la abre —o la hereda— el servicio: este bean no la declara, porque si la
 * declarara aca y no alla, un llamado interno al servicio quedaria sin ella.
 */
@Component
public class PersonConsumoDeAutorizaciones implements ConsumoDeAutorizaciones {

	private final ConsumoDeAutorizacionService consumo;

	public PersonConsumoDeAutorizaciones(ConsumoDeAutorizacionService consumo) {
		this.consumo = consumo;
	}

	@Override
	public List<ResultadoDeConsumo> consumirPorSesion(ConsumoPorSesion hecho) {
		return consumo.consumirPorSesion(hecho);
	}
}
