import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// Al final de la corrida: lo que la guia pide correr al final (baja de sede, baja de persona), el
// ausente (necesita que el horario haya pasado) y los pasos que son solo de pantalla.
export async function cfinal() {
	await paso('PER-14', 'Selector explicable: de que autorizacion se descontaria', async () => {
		need(C.cobertura, C.orden, C.practica2);
		const s = await S('admin');
		const nueva = async (practicaId, numero, hasta) => must(await api('POST', `/api/v1/personas/${C.persona}/autorizaciones`, { s, json: { coberturaId: C.cobertura, ordenMedicaId: C.orden, practicaId, numero, cantidadAutorizada: 5, estadoInicial: 'APROBADA', vigenciaDesde: hasta > '2027' ? '2027-01-01' : '2026-01-01', vigenciaHasta: hasta } }), 201, `autorizacion ${numero}`).id;
		const sup = await api('POST', `/api/v1/personas/${C.persona}/autorizaciones`, { s, json: { coberturaId: C.cobertura, ordenMedicaId: C.orden, practicaId: C.practica, numero: 'AUT-QA-002', cantidadAutorizada: 5, estadoInicial: 'APROBADA', vigenciaDesde: '2026-01-01', vigenciaHasta: hoy(20) } });
		if (sup.tipo === 'autorizacion-superpuesta') guia('PER-14 pide una segunda autorizacion APROBADA de la misma practica que venza antes que la de PER-10; el producto rechaza dos aprobadas que se pisan (409 autorizacion-superpuesta). Solo conviven consecutivas, asi que el desempate "la que vence antes" no se puede armar con dos vigentes el mismo dia');
		const antes = C.autorizacion;
		const futura = await nueva(C.practica, 'AUT-QA-002b', '2027-06-30').catch(() => null);
		const otra = await nueva(C.practica2, 'AUT-QA-003', hoy(10));
		const el = must(await api('GET', `/api/v1/personas/${C.persona}/autorizaciones/elegibles?fecha=${hoy()}`, { s: await S('adm') }), 200, 'consulta de elegibles');
		const lista = Array.isArray(el) ? el : el.content ?? el.candidatas ?? [];
		const usaria = lista.filter((x) => x.practicaId === C.practica && x.habilita)[0];
		eq(usaria?.id, antes, 'de la practica, la que se usaria es la vigente con saldo');
		ok(lista.some((x) => x.id === otra), 'la de otra practica aparece entre las candidatas', JSON.stringify(lista).slice(0, 600));
		const f = q(`SELECT id, practica_id, numero, estado, cantidad_autorizada - cantidad_consumida AS disponibles, vigencia_desde, vigencia_hasta FROM autorizacion WHERE persona_id = @persona ORDER BY vigencia_hasta, id`);
		const esperada = f.find((x) => n(x.practica_id) === C.practica && x.estado === 'APROBADA' && n(x.disponibles) > 0 && x.vigencia_desde <= hoy() && x.vigencia_hasta >= hoy());
		eq(n(esperada?.id), antes, 'la base dice lo mismo', f);
		nota(`autorizacion consecutiva (renovacion) creada: ${futura ? 'si' : 'no'}`);
		const at = await atencionCompleta(C.persona, { cobertura: C.cobertura });
		if (at.error) { st(at.error, [200, 201], 'cerrar una sesion de esa practica'); return; }
		const m = q(`SELECT autorizacion_id FROM autorizacion_movimiento WHERE tipo = 'CONSUMO' AND referencia_origen = ${at.sesion.id}`);
		eq(n(m[0]?.autorizacion_id), antes, 'el CONSUMO del cierre cae en esa misma autorizacion (defecto de 06.04 si no)', m);
		manual('el selector de la pantalla con el "por que no" de cada candidata');
	});
	await paso('PER-13', 'Baja de la persona', async () => {
		need(C.persona_dup);
		const d0 = maxAudit();
		const s = await S('admin');
		await api('POST', `/api/v1/personas/${C.persona_dup}/perfil-paciente`, { s, json: { motivo: 'para la baja' } });
		const p = must(await api('GET', `/api/v1/personas/${C.persona_dup}`, { s }), 200, 'leer');
		must(await api('DELETE', `/api/v1/personas/${C.persona_dup}`, { s, json: { expectedVersion: p.version, motivo: 'Duplicado (pre-QA)' } }), 200, 'dar de baja');
		const f = q(`SELECT p.active, p.deleted_at, p.deactivation_reason, pp.active AS perfil_activo, pp.deleted_at AS perfil_baja FROM persona p LEFT JOIN perfil_paciente pp ON pp.persona_id = p.id WHERE p.id = ${C.persona_dup}`);
		ok(f[0]?.active === '0' && f[0].deleted_at && f[0].deactivation_reason && f[0].perfil_activo === '0' && f[0].perfil_baja, 'persona y perfil dados de baja logica', f);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'PERSONA_DEACTIVATED', (x) => x.reason), 'PERSONA_DEACTIVATED con el motivo', au);
		if (!tiene(au, 'PERFIL_PACIENTE_DEACTIVATED') && tiene(au, 'PERSONA_DEACTIVATED', (x) => det(x).perfilPacienteDadoDeBaja)) guia('PER-13 espera PERFIL_PACIENTE_DEACTIVATED aparte; la baja del perfil queda en el mismo PERSONA_DEACTIVATED (details.perfilPacienteDadoDeBaja)');
		else ok(tiene(au, 'PERFIL_PACIENTE_DEACTIVATED'), 'la baja del perfil queda auditada', au);
	});
	await paso('AGE-05', 'Marcar ausente no libera el lugar', async () => {
		need(C.turno_ausente);
		const d0 = maxAudit();
		const espera = new Date(C.turno_ausente.inicio).getTime() - Date.now() + 5000;
		if (espera > 0) { nota(`se espero ${Math.round(espera / 1000)} s a que el turno empezara`); await dormir(espera); }
		const t = q1(`SELECT version FROM turno WHERE id = ${C.turno_ausente.id}`);
		must(await api('POST', `/api/v1/consultorios/${C.sede_a}/turnos/${C.turno_ausente.id}/ausencia`, { s: await S('adm'), json: { expectedVersion: n(t.version), motivo: 'No vino (pre-QA)' } }), 200, 'marcar ausente');
		const f = q(`SELECT id, estado, ausente_en, deleted_at FROM turno WHERE id = ${C.turno_ausente.id}`);
		ok(f[0].estado === 'AUSENTE' && f[0].ausente_en && f[0].deleted_at === null, 'AUSENTE con deleted_at NULL (la hora se consumio)', f);
		ok(q(`SELECT 1 FROM turno_evento WHERE turno_id = ${C.turno_ausente.id} AND tipo = 'AUSENCIA'`).length === 1, 'evento AUSENCIA');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'TURNO_AUSENTE'), 'TURNO_AUSENTE');
	});
	await paso('ORG-03c', 'Baja de la sede 2: turnos pendientes, y reservar en una sede de baja', async () => {
		need(C.sede_a2, C.servicio);
		const d0 = maxAudit();
		const a = await S('admin');
		const ctx2 = await entrar(CU.admin.email, { org: C.org_a, sede: C.sede_a2 }).catch(() => null);
		need(ctx2);
		const m2 = await api('POST', '/api/v1/memberships', { s: a, json: { email: CU.prof.email, roleCode: 'PROFESIONAL', consultorioId: C.sede_a2, reason: 'Profesional de la sede 2 (pre-QA)' } });
		must(m2, 201, 'el profesional tambien en la sede 2');
		const mp2 = m2.cuerpo.membershipId;
		for (let d = 1; d <= 7; d++) await api('POST', `/api/v1/consultorios/${C.sede_a2}/profesionales/${mp2}/disponibilidad`, { s: ctx2, json: { diaSemana: d, horaDesde: '00:00', horaHasta: '23:45' } });
		const of2 = must(await api('POST', `/api/v1/consultorios/${C.sede_a2}/ofertas`, { s: ctx2, json: { servicioId: C.servicio, nombreComercial: `Sede 2 QA ${suf}`, capacidad: 1, duracionMinutos: 30, requiereProfesional: true, requiereEspacio: false, admiteObraSocial: false } }), 201, 'oferta de la sede 2').id;
		let k = 1;
		while ([0, 6].includes(new Date(`${hoy(k)}T12:00:00Z`).getUTCDay())) k++;
		const ag = await agenda(ctx2, C.sede_a2, of2, hoy(k), hoy(k + 1), mp2);
		const sl = (ag.cuerpo?.dias ?? []).flatMap((x) => x.slots);
		need(sl[1]);
		const t1 = must(await reservar(ctx2, C.persona, sl[0], { oferta: of2, sede: C.sede_a2, prof: mp2 }), [200, 201], 'turno 1 en la sede 2');
		const t2 = must(await reservar(ctx2, C.persona_part, sl[1], { oferta: of2, sede: C.sede_a2, prof: mp2 }), [200, 201], 'turno 2 en la sede 2');
		const baja = () => api('POST', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a2}/deactivate`, { s: a, json: { reason: 'Cierre de la sede 2 (pre-QA)' } });
		const r1 = await baja();
		tipo(r1, 'consultorio-has-active-references', 'baja con turnos pendientes');
		ok(r1.cuerpo?.referenceType === 'turnos-futuros' && r1.cuerpo?.referenceCount === 2, 'referenceType turnos-futuros y referenceCount 2', r1);
		for (const t of [t1, t2]) must(await api('POST', `/api/v1/consultorios/${C.sede_a2}/turnos/${t.id}/cancelacion`, { s: ctx2, json: { expectedVersion: t.version, motivo: 'Cierre de sede' } }), 200, 'cancelar');
		must(await baja(), 200, 'baja sin turnos pendientes');
		const sl3 = sl[2] ?? sl[0];
		const rA = await reservar(a, C.persona, sl3, { oferta: of2, sede: C.sede_a2, prof: mp2 });
		tipo(rA, 'consultorio-inactive', 'reservar en la sede de baja con contexto en la sede A');
		if (ctx2) st(await reservar(ctx2, C.persona, sl3, { oferta: of2, sede: C.sede_a2, prof: mp2 }), 404, 'con el token de contexto en la sede 2');
		else nota('no se pudo obtener un token con contexto en la sede 2');
		const c = q('SELECT active, deleted_at, deactivation_reason FROM consultorio WHERE id = @sede_a2');
		ok(c[0].active === '0' && c[0].deleted_at && c[0].deactivation_reason, 'active = 0 con deleted_at y motivo', c);
		const vivos = q("SELECT COUNT(*) n FROM turno t JOIN consultorio c ON c.id = t.consultorio_id WHERE c.organization_id = @org_a AND c.active = 0 AND t.deleted_at IS NULL AND t.estado IN ('RESERVADO','CONFIRMADO')");
		eq(vivos[0].n, 0, 'turnos vivos en sede de baja = 0 (alto si falla)', vivos);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'CONSULTORIO_DEACTIVATED', (x) => x.reason), 'CONSULTORIO_DEACTIVATED con reason');
	});
	await paso('AGE-13', '"Hoy" es el dia de la sede, no el del navegador', async () => {
		const f = q('SELECT timezone, DATE(CONVERT_TZ(UTC_TIMESTAMP(), \'+00:00\', timezone)) AS hoy_de_la_sede FROM consultorio WHERE id = @sede_a');
		nota(`hoy de la sede en la base: ${f[0]?.hoy_de_la_sede} (${f[0]?.timezone})`);
		soloManual('todo el paso es de la pantalla: zona del navegador en DevTools y la fecha que propone cada pantalla');
	});
	await paso('AIS-02', 'Lo mismo por pantalla', async () => {
		soloManual('pegar en la barra las URLs de A con admin-b y mirar la consola; por la API lo cubre AIS-01');
	});
}
