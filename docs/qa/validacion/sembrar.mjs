#!/usr/bin/env node
// Sembrado MINIMO por la API para validar las consultas SQL de la guia de QA (AKINE G-12).
// Node 18+, sin dependencias. NO es el QA: solo deja al menos una fila en las tablas que las
// consultas leen, para que `validar-consultas.mjs` corra cada una contra el esquema real.
//
//   QA_API_URL=http://localhost:8099 QA_MAILPIT_URL=http://localhost:8049 \
//     node docs/qa/validacion/sembrar.mjs <estado.json>
//
// Todo pasa por la API publica, nada por SQL. Las cuentas son sinteticas (@ejemplo.test) y la
// contrasena es un valor de prueba de una base efimera que se borra con `stack.sh down`.
//
// Escribe en <estado.json> los ids que la guia usa como variables de MySQL (@org_a, @sede_a, ...).

import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const API = (process.env.QA_API_URL ?? 'http://localhost:8099').replace(/\/$/, '');
const MAILPIT = (process.env.QA_MAILPIT_URL ?? 'http://localhost:8049').replace(/\/$/, '');
const PLATAFORMA = process.env.QA_PLATAFORMA_EMAIL ?? 'plataforma.qa@ejemplo.test';
const PASSWORD = 'qa-g12-contrasena-sintetica-2026';
const archivo = process.argv[2];
if (!archivo) {
	console.error('uso: sembrar.mjs <estado.json>');
	process.exit(2);
}
const sufijo = Date.now().toString(36);
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

async function http(metodo, ruta, { token, json, headers = {}, esperado = [200, 201, 202, 204] } = {}) {
	for (let intento = 0; ; intento++) {
		const res = await fetch(`${API}${ruta}`, {
			method: metodo,
			headers: {
				Accept: 'application/json, application/problem+json',
				...(token ? { Authorization: `Bearer ${token}` } : {}),
				...(json !== undefined ? { 'Content-Type': 'application/json' } : {}),
				...headers,
			},
			body: json !== undefined ? JSON.stringify(json) : undefined,
		});
		const texto = await res.text();
		if (res.status === 429 && intento < 20) {
			await dormir(Math.min(65, Number(res.headers.get('retry-after') ?? 10)) * 1000);
			continue;
		}
		if (!esperado.includes(res.status)) {
			throw new Error(`${metodo} ${ruta}: HTTP ${res.status} ${texto.slice(0, 400)}`);
		}
		try {
			return { status: res.status, cuerpo: JSON.parse(texto) };
		} catch {
			return { status: res.status, cuerpo: texto };
		}
	}
}
const pedir = async (...a) => (await http(...a)).cuerpo;

async function enlace(email, asunto) {
	for (let i = 0; i < 90; i++) {
		const q = encodeURIComponent(`to:"${email}"${asunto ? ` subject:"${asunto}"` : ''}`);
		const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${q}`)).json();
		if (r.messages?.length) {
			const m = await (await fetch(`${MAILPIT}/api/v1/message/${r.messages[0].ID}`)).json();
			const t = /[?&]token=([A-Za-z0-9_-]+)/.exec(`${m.Text ?? ''} ${m.HTML ?? ''}`)?.[1];
			if (t) return t;
		}
		await dormir(2000);
	}
	throw new Error(`no llego el correo para ${email}`);
}

async function registrar(email, organizacion) {
	await pedir('POST', '/api/v1/auth/register', {
		headers: { 'Idempotency-Key': randomUUID() },
		esperado: [202],
		json: { email, password: PASSWORD, firstName: 'QA', lastName: `Sintetica ${sufijo}`, organizationName: organizacion },
	});
	await pedir('POST', '/api/v1/auth/activate', { json: { token: await enlace(email, 'Activa') } });
}

/** Login + contexto. Devuelve { token, organizationId, consultorioId }. */
async function entrar(email, organizationId) {
	const pre = (await pedir('POST', '/api/v1/auth/login', { json: { email, password: PASSWORD } })).accessToken;
	const contextos = await pedir('GET', '/api/v1/me/contexts', { token: pre });
	const c = organizationId ? contextos.find((x) => x.organizationId === organizationId) : contextos[0];
	if (!c) return { token: pre };
	const ctx = await pedir('POST', '/api/v1/auth/context', {
		token: pre, json: { organizationId: c.organizationId, consultorioId: c.consultorioId },
	});
	return { token: ctx.accessToken, organizationId: c.organizationId, consultorioId: c.consultorioId };
}

let zona = 'UTC';
const hoy = (dias = 0) => new Date(Date.now() + dias * 86400000).toLocaleDateString('en-CA', { timeZone: zona });
const paso = (t) => console.log(`[sembrado] ${t}`);
const e = {};

// --- Plataforma: bootstrap de DP-14, servicio global y una practica global ---------------------
paso('admin de plataforma (enlace del bootstrap DP-14)');
let plat;
try {
	plat = (await pedir('POST', '/api/v1/auth/login', { json: { email: PLATAFORMA, password: PASSWORD } })).accessToken;
} catch {
	await pedir('POST', '/api/v1/auth/activate', { json: { token: await enlace(PLATAFORMA), password: PASSWORD } });
	plat = (await pedir('POST', '/api/v1/auth/login', { json: { email: PLATAFORMA, password: PASSWORD } })).accessToken;
}
const servicio = await pedir('POST', '/api/v1/servicios', {
	token: plat,
	json: { codigo: `QA_KINE_${sufijo}`.toUpperCase(), nombre: `Kinesiologia QA ${sufijo}`, naturaleza: 'TERAPEUTICO', modalidadDefault: 'INDIVIDUAL' },
});
e.servicio = servicio.id;

// --- Centro A, profesional, admin del centro B --------------------------------------------------
const correo = (r) => `qa.${r}.${sufijo}@ejemplo.test`;
paso('altas self-service (5 por minuto)');
await registrar(correo('admin-a'), `Centro QA A ${sufijo}`);
await registrar(correo('profesional'), `Propio profesional ${sufijo}`);
await registrar(correo('administrativo'), `Propio administrativo ${sufijo}`);
await registrar(correo('paciente'), `Propio paciente ${sufijo}`);
await registrar(correo('admin-b'), `Centro QA B ${sufijo}`);

const a = await entrar(correo('admin-a'));
e.org_a = a.organizationId;
e.sede_a = a.consultorioId;
zona = (await pedir('GET', `/api/v1/organizations/${e.org_a}/consultorios/${e.sede_a}`, { token: a.token })).timezone;
const b = await entrar(correo('admin-b'));
e.org_b = b.organizationId;
e.sede_b = b.consultorioId;

paso('memberships en A: PROFESIONAL, ADMINISTRATIVO, PACIENTE; y admin-a como ORG_ADMIN en B (#70)');
const alta = (tok, email, roleCode, consultorioId) =>
	pedir('POST', '/api/v1/memberships', { token: tok, json: { email, roleCode, consultorioId, reason: 'Sembrado QA G-12' } });
e.membership_profesional = (await alta(a.token, correo('profesional'), 'PROFESIONAL', e.sede_a)).membershipId;
e.membership_administrativo = (await alta(a.token, correo('administrativo'), 'ADMINISTRATIVO', e.sede_a)).membershipId;
e.membership_paciente = (await alta(a.token, correo('paciente'), 'PACIENTE', e.sede_a)).membershipId;
e.membership_admin_a_en_b = (await alta(b.token, correo('admin-a'), 'ORG_ADMIN', undefined)).membershipId;
await pedir('POST', `/api/v1/organizations/${e.org_a}/memberships/${e.membership_profesional}/grants`, {
	token: a.token, json: { permissionCode: 'paciente:manage', reason: 'Sembrado QA G-12' },
});

paso('disponibilidad, horario, oferta, practica, precio');
for (let d = 1; d <= 7; d++) {
	await pedir('POST', `/api/v1/consultorios/${e.sede_a}/profesionales/${e.membership_profesional}/disponibilidad`, {
		token: a.token, json: { diaSemana: d, horaDesde: '00:00', horaHasta: '23:45' },
	});
}
const especialidad = await pedir('POST', '/api/v1/catalogos/especialidades', {
	token: a.token, json: { codigo: `QA-E-${sufijo}`, name: 'Especialidad QA', alcance: 'ORGANIZACION' },
});
e.especialidad = especialidad.id;
const practica = await pedir('POST', '/api/v1/catalogos/practicas', {
	token: a.token, json: { codigo: `QA-P-${sufijo}`, name: 'Practica QA', alcance: 'ORGANIZACION', especialidadId: e.especialidad },
});
e.practica = practica.id;
let oferta = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/ofertas`, {
	token: a.token,
	json: { servicioId: e.servicio, nombreComercial: `Kinesio QA ${sufijo}`, capacidad: 1, duracionMinutos: 30, requiereProfesional: true, requiereEspacio: false, admiteObraSocial: true },
});
e.oferta = oferta.id;
const pr = await pedir('PUT', `/api/v1/consultorios/${e.sede_a}/ofertas/${e.oferta}/practicas`, {
	token: a.token, json: { expectedVersion: oferta.version, practicaIds: [e.practica], practicaPrincipalId: e.practica },
});
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/ofertas/${e.oferta}/precios-particulares`, {
	token: a.token, json: { importe: 10000, moneda: 'ARS', vigenciaDesde: hoy() },
});

paso('financiador, plan, convenio, arancel');
const fin = await pedir('POST', '/api/v1/financiadores', { token: a.token, json: { codigo: `QAOS${sufijo}`.toUpperCase(), nombre: 'Obra Social QA', tipo: 'OBRA_SOCIAL' } });
e.financiador = fin.id;
const plan = await pedir('POST', `/api/v1/financiadores/${e.financiador}/planes`, {
	token: a.token, json: { codigo: 'QA-PLAN', nombre: 'Plan QA', vigenciaDesde: '2026-01-01', requiereAutorizacion: true },
});
e.plan = plan.id;
const conv = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/convenios`, {
	token: a.token,
	json: { codigo: 'QA-CONV', nombre: 'Convenio QA', financiadorId: e.financiador, planId: e.plan, modalidad: 'POR_PRESTACION', moneda: 'ARS', vigenciaDesde: '2026-01-01', requiereAutorizacion: true },
});
e.convenio = conv.id;
const ar = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/convenios/${e.convenio}/aranceles`, {
	token: a.token, json: { practicaId: e.practica, ofertaId: e.oferta, importeTotal: 8000, importeFinanciador: 6000, coseguro: 2000, vigenciaDesde: '2026-01-01' },
});
e.arancel = ar.id;

paso('personas: con obra social, particular, y una del centro B');
const persona = async (tok, apellido) => {
	const p = await pedir('POST', '/api/v1/personas', { token: tok, json: { nombre: 'Paciente', apellido, numeroDocumento: `${Date.now() % 100000000}`, tipoDocumento: 'DNI' } });
	await pedir('POST', `/api/v1/personas/${p.id}/perfil-paciente`, { token: tok, json: {} });
	return p.id;
};
e.persona = await persona(a.token, `ObraSocial${sufijo}`);
e.persona_part = await persona(a.token, `Particular${sufijo}`);
e.persona_b = await persona(b.token, `CentroB${sufijo}`);
const cob = await pedir('POST', `/api/v1/personas/${e.persona}/coberturas`, {
	token: a.token, json: { tipo: 'FINANCIADA', planId: e.plan, numeroAfiliado: `AF-${sufijo}`, vigenciaDesde: '2026-01-01', principal: true },
});
e.cobertura = cob.id;
const orden = await pedir('POST', `/api/v1/personas/${e.persona}/ordenes`, {
	token: a.token, json: { coberturaId: e.cobertura, fechaEmision: hoy(), profesionalEmisor: 'Dra. Sintetica', sesionesPrescriptas: 10 },
});
e.orden = orden.id;
const aut = await pedir('POST', `/api/v1/personas/${e.persona}/autorizaciones`, {
	token: a.token, json: { coberturaId: e.cobertura, ordenMedicaId: e.orden, practicaId: e.practica, numero: `AUT-${sufijo}`, cantidadAutorizada: 10, estadoInicial: 'APROBADA', vigenciaDesde: '2026-01-01' },
});
e.autorizacion = aut.id;

paso('turnos de hoy, recepcion, sesion cerrada (obra social y particular)');
const agenda = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/ofertas/${e.oferta}/agenda?desde=${hoy()}&hasta=${hoy(1)}`, { token: a.token });
const futuros = agenda.dias.flatMap((d) => d.slots).filter((s) => new Date(s.desde).getTime() > Date.now() + 120000);
if (futuros.length < 4) throw new Error('quedan menos de 4 slots hoy: correr el sembrado antes de las 22 de la sede');
const reservar = (personaId, slot) => pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/ofertas/${e.oferta}`, {
	token: a.token, json: { inicio: slot.desde, personaId, profesionalId: e.membership_profesional, idempotencyKey: randomUUID() },
});
const t1 = await reservar(e.persona, futuros[0]);
const t2 = await reservar(e.persona_part, futuros[1]);
const t3 = await reservar(e.persona_part, futuros[2]);
e.turno = t1.id;
e.turno_part = t2.id;
e.turno_cancelado = t3.id;
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/${t3.id}/cancelacion`, { token: a.token, json: { expectedVersion: t3.version, motivo: 'Cancelacion sintetica QA' } });

const rec = async (turnoId, cobertura) => {
	let r = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/${turnoId}/recepcion`, { token: a.token });
	r = cobertura
		? await pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/${turnoId}/recepcion/validacion`, { token: a.token, json: { coberturaId: cobertura, expectedVersion: r.version } })
		: await pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/${turnoId}/recepcion/particular`, { token: a.token, json: { motivo: 'Particular sintetico QA', expectedVersion: r.version } });
	r = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/${turnoId}/recepcion/espera`, { token: a.token, json: { expectedVersion: r.version } });
	await pedir('POST', `/api/v1/consultorios/${e.sede_a}/turnos/${turnoId}/recepcion/llamado`, { token: a.token, json: { expectedVersion: r.version } });
};
await rec(e.turno, e.cobertura);
await rec(e.turno_part, null);

const prof = await entrar(correo('profesional'), e.org_a);
const cerrar = async (turnoId) => {
	let s = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/sesiones/turnos/${turnoId}`, { token: prof.token });
	const tr = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/sesiones/${s.id}/tratamientos`, {
		token: prof.token, json: { practicaId: e.practica, version: s.version, duracionMinutos: 20, zona: 'Rodilla', lateralidad: 'DERECHA' },
	});
	s = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/sesiones/${s.id}`, { token: prof.token });
	await pedir('POST', `/api/v1/consultorios/${e.sede_a}/sesiones/${s.id}/cierre`, {
		token: prof.token, json: { asistencia: 'PRESENTE', notaDeCierre: 'Sesion sintetica QA', version: s.version },
	});
	return s.id;
};
e.sesion = await cerrar(e.turno);
e.sesion_part = await cerrar(e.turno_part);
const sesionCerrada = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/sesiones/${e.sesion_part}`, { token: prof.token });
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/sesiones/${e.sesion_part}/enmiendas`, {
	token: prof.token, json: { motivo: 'Enmienda sintetica QA', notaDeCierre: 'Nota enmendada QA', version: sesionCerrada.version },
});

paso('historia clinica, entrada y caso (con justificacion)');
const J = { 'X-Justificacion-Acceso': 'QA G-12: atencion sintetica' };
const hc = await pedir('PUT', `/api/v1/historias-clinicas/por-persona/${e.persona}`, { token: prof.token, headers: J });
e.historia = hc.id;
await pedir('POST', `/api/v1/historias-clinicas/${e.historia}/entradas`, { token: prof.token, headers: J, json: { tipo: 'EVOLUCION', cuerpo: 'Entrada sintetica QA' } });
const caso = await pedir('POST', `/api/v1/historias-clinicas/${e.historia}/casos`, {
	token: prof.token, headers: J, json: { diagnosticoPresuntivo: 'Diagnostico sintetico QA', ofertaId: e.oferta },
});
e.caso = caso.id;

paso('caja, cobro con imputacion, anulacion, egreso');
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/caja/jornadas`, { token: a.token, json: { moneda: 'ARS', saldoInicial: 5000 } });
const obls = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/obligaciones?personaId=${e.persona_part}`, { token: a.token });
const deuda = obls.find((o) => o.responsable === 'PACIENTE') ?? obls[0];
e.obligacion_part = deuda.id;
const cobro = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros`, {
	token: a.token,
	json: { personaId: e.persona_part, total: deuda.saldo, moneda: 'ARS', medios: [{ medio: 'EFECTIVO', importe: deuda.saldo }], imputaciones: [{ obligacionId: deuda.id, importe: deuda.saldo }], idempotencyKey: randomUUID() },
});
e.cobro = cobro.id;
const anticipo = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros`, {
	token: a.token, json: { personaId: e.persona_part, total: 500, anticipo: 500, moneda: 'ARS', medios: [{ medio: 'EFECTIVO', importe: 500 }], idempotencyKey: randomUUID() },
});
e.cobro_anulado = anticipo.id;
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros/${anticipo.id}/anulacion`, { token: a.token, json: { motivo: 'Anulacion sintetica QA' } });
// Antes de que un egreso saque efectivo: se anula el cobro que saldaba la deuda particular, para que vuelva a tener saldo.
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros/${e.cobro}/anulacion`, { token: a.token, json: { motivo: 'Anulacion sintetica para reimputar' } });
const egreso = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/egresos`, {
	token: a.token, json: { categoria: 'INSUMOS', tipoBeneficiario: 'EXTERNO', beneficiarioNombre: 'Proveedor QA', concepto: 'Insumos QA', importeTotal: 1000, moneda: 'ARS', comprobanteTipo: 'FACTURA_B', comprobanteNumero: `B-${sufijo}`, comprobanteFecha: hoy() },
});
e.egreso = egreso.id;
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/egresos/${e.egreso}/confirmacion`, { token: a.token });
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/egresos/${e.egreso}/pagos`, { token: a.token, json: { importe: 1000, medio: 'EFECTIVO', idempotencyKey: randomUUID() } });

paso('presentacion al financiador: borrador, confirmar, factura, debito, pago');
// La cuenta corriente del paciente NO lista la deuda del financiador (#49): se busca en la bandeja.
const elegibles = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/prestaciones-elegibles?financiadorId=${e.financiador}`, { token: a.token });
if (!elegibles.length) throw new Error('la sesion de obra social no devengo obligacion del financiador');
e.obligacion_fin = elegibles[0].id;
let pres = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/presentaciones`, {
	token: a.token, json: { financiadorId: e.financiador, moneda: 'ARS', periodoDesde: '2026-01-01', periodoHasta: '2026-12-31', obligacionIds: [e.obligacion_fin] },
});
e.presentacion = pres.id;
const P = `/api/v1/consultorios/${e.sede_a}/presentaciones/${e.presentacion}`;
await pedir('POST', `${P}/confirmacion`, { token: a.token });
await pedir('POST', `${P}/factura`, { token: a.token, json: { numero: `FC-${sufijo}`, fecha: hoy() } });
pres = await pedir('GET', P, { token: a.token });
const item = (pres.items ?? [])[0];
if (item) {
	await pedir('POST', `${P}/items/${item.id}/debito`, { token: a.token, json: { importe: 1000, motivo: 'Debito sintetico QA' } });
}
await pedir('POST', `${P}/pagos`, { token: a.token, json: { importe: 5000, medio: 'TRANSFERENCIA', fechaPago: hoy(), idempotencyKey: randomUUID() } });
await pedir('POST', `${P}/conciliacion`, { token: a.token });
// Un borrador anulado, con la obligacion debitada que quedo libre.
const borrador = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/presentaciones`, {
	token: a.token, json: { financiadorId: e.financiador, moneda: 'ARS', periodoDesde: '2026-01-01', periodoHasta: '2026-12-31', obligacionIds: [e.obligacion_fin] },
});
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/presentaciones/${borrador.id}/anulacion`, { token: a.token, json: { motivo: 'Borrador sintetico QA' } });

paso('coseguro anulado (DP-13): alerta y reversion manual del consumo');
const obOs = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/obligaciones?personaId=${e.persona}`, { token: a.token });
const coseguro = obOs.find((o) => o.concepto === 'COSEGURO');
if (coseguro) {
	await pedir('DELETE', `/api/v1/consultorios/${e.sede_a}/obligaciones/${coseguro.id}`, { token: a.token, json: { motivo: 'Anulacion sintetica QA', version: coseguro.version } });
	const movs = await pedir('GET', `/api/v1/autorizaciones/${e.autorizacion}/movimientos`, { token: a.token });
	const consumo = (Array.isArray(movs) ? movs : movs.content ?? []).find((m) => m.tipo === 'CONSUMO');
	if (consumo) {
		await pedir('POST', `/api/v1/autorizaciones/${e.autorizacion}/reversiones`, { token: a.token, json: { movimientoId: consumo.id, motivo: 'Reversion sintetica QA' } });
	}
}

paso('anticipo, imputacion posterior y reintegro; cierre de caja');
const adelanto = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros`, {
	token: a.token, json: { personaId: e.persona_part, total: 3000, anticipo: 3000, moneda: 'ARS', medios: [{ medio: 'EFECTIVO', importe: 3000 }], idempotencyKey: randomUUID() },
});
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros/${adelanto.id}/imputaciones`, { token: a.token, json: { obligacionId: e.obligacion_part, importe: 2000, idempotencyKey: randomUUID() } });
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/cobros/${adelanto.id}/reintegros`, { token: a.token, json: { importe: 400, medio: 'EFECTIVO', motivo: 'Reintegro sintetico QA', idempotencyKey: randomUUID() } });
e.cobro_anticipo = adelanto.id;
const jornadas = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/caja/jornadas?estado=ABIERTA&limite=1`, { token: a.token });
const jornada = await pedir('GET', `/api/v1/consultorios/${e.sede_a}/caja/jornadas/${jornadas[0].id}`, { token: a.token });
e.jornada = jornada.id;
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/caja/jornadas/${jornada.id}/cierre`, {
	token: a.token, json: { saldoTeoricoEsperado: jornada.saldoTeorico, saldoDeclarado: Math.max(0, jornada.saldoTeorico - 100), motivoDiferencia: 'Diferencia sintetica QA' },
});

paso('espacio, catalogo pedido y aprobado, excepcion, serie, plan de tratamiento, medicion');
await pedir('POST', `/api/v1/organizations/${e.org_a}/consultorios/${e.sede_a}/espacios`, { token: a.token, json: { name: 'Box QA', tipo: 'BOX', capacidad: 1 } });
const solicitud = await pedir('POST', '/api/v1/catalogo-solicitudes', {
	token: a.token, json: { tipo: 'PRACTICA', nombrePropuesto: `Magnetoterapia QA ${sufijo}`, codigoPropuesto: `QA-MAG-${sufijo}`, justificacion: 'Sembrado QA G-12' },
});
e.solicitud = solicitud.id;
const especialidadGlobal = await pedir('POST', '/api/v1/catalogos/especialidades', { token: plat, json: { codigo: `QA-EG-${sufijo}`, name: `Especialidad global QA ${sufijo}`, alcance: 'GLOBAL' } });
await pedir('POST', `/api/v1/catalogo-solicitudes/${solicitud.id}/resolve`, {
	token: plat, json: { estado: 'APROBADA', nota: 'Aprobada en el sembrado QA', version: solicitud.version ?? 0, especialidadId: especialidadGlobal.id },
});
await pedir('POST', `/api/v1/consultorios/${e.sede_a}/excepciones`, {
	token: a.token, json: { membershipId: e.membership_profesional, tipo: 'CIERRE', motivo: 'LICENCIA', fechaDesde: hoy(30), fechaHasta: hoy(31) },
});
const serie = await pedir('POST', `/api/v1/consultorios/${e.sede_a}/series-de-turnos`, {
	token: a.token,
	json: { ofertaId: e.oferta, personaId: e.persona_part, profesionalId: e.membership_profesional, diasSemana: [1, 3], hora: '10:00', fechaDesde: hoy(7), cantidad: 4, idempotencyKey: randomUUID() },
});
e.serie = serie.id ?? serie.serieId;
const planTratamiento = await pedir('POST', `/api/v1/casos-clinicos/${e.caso}/planes`, {
	token: prof.token, headers: J, json: { objetivos: 'Objetivo sintetico QA', frecuenciaSemanal: 2, duracionSemanas: 4, items: [{ ofertaId: e.oferta, cantidadPlanificada: 8 }] },
});
e.plan_tratamiento = planTratamiento.id;
await pedir('POST', `/api/v1/planes-tratamiento/${planTratamiento.id}/activacion`, { token: prof.token, headers: J, json: { expectedVersion: planTratamiento.version } });
await pedir('POST', '/api/v1/mediciones/definiciones', {
	token: a.token, json: { codigo: `QA-FLEX-${sufijo}`, name: 'Flexion QA', tipo: 'NUMERICO', unidad: 'grados', minimo: 0, maximo: 160, alcance: 'ORGANIZACION' },
});

await pedir('POST', `/api/v1/organizations/${e.org_a}/colaborador-invitaciones`, {
	token: a.token, json: { email: correo('invitado'), roleCode: 'ADMINISTRATIVO', consultorioId: e.sede_a },
});

paso('soporte de plataforma sobre el centro A');
const sa = await pedir('POST', `/api/v1/platform/organizations/${e.org_a}/support-access`, { token: plat, json: { reason: 'Soporte sintetico QA G-12', durationMinutes: 30 } });
e.support_access = sa.id;

paso('lecturas que deben quedar auditadas / denegadas');
const pac = await entrar(correo('paciente'), e.org_a);
await http('GET', `/api/v1/personas?size=5`, { token: pac.token, esperado: [403] });
await http('GET', `/api/v1/personas/${e.persona_b}`, { token: a.token, esperado: [404] });

writeFileSync(archivo, JSON.stringify({ ...e, sufijo, cuentas: Object.fromEntries(['admin-a', 'profesional', 'administrativo', 'paciente', 'admin-b'].map((r) => [r, correo(r)])) }, null, 2));
console.log(`[sembrado] listo: ${archivo}`);
