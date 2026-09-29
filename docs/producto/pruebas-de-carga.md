# Pruebas de carga — harness identificado

> **Estado: identificado, NO ejecutado.**
>
> La etapa AKINE-00.02 pide explícitamente *"Carga: solo identificar harness; no lanzar
> contra entornos compartidos"*. Este documento cumple esa instrucción: elige la
> herramienta y deja el diseño listo. **No se ejecutó ninguna prueba de carga**, y no
> corresponde hacerlo hasta que exista dominio funcional que medir.

## Por qué todavía no se ejecuta

Hoy el backend expone dos endpoints técnicos —`/api/v1/version` y `/actuator/health`— que
no tocan lógica de negocio ni consultas reales. Medir su rendimiento produciría un número
impresionante y **completamente inútil**: no representa ninguna operación que un usuario
vaya a hacer.

Peor: un número así invita a tratarlo como línea base, y la primera etapa funcional que
lo empeore parecerá una regresión cuando en realidad solo empezó a hacer trabajo real.

Las pruebas de carga arrancan cuando exista un flujo end-to-end representativo. El primer
candidato natural es **la agenda** (M12): es el que más concurrencia real va a tener —varios
administrativos reservando turnos sobre los mismos slots— y donde una condición de carrera
tiene consecuencia visible para el negocio.

## Harness elegido: k6

**Elegido:** [k6](https://k6.io) (Grafana Labs).

| Criterio | Por qué k6 |
|---|---|
| Lenguaje | Escenarios en JavaScript. El equipo ya escribe TS para el frontend y los E2E de Playwright: no hay lenguaje nuevo que aprender |
| CI | Binario único, sin JVM ni GUI. Corre en un job de GitHub Actions sin infraestructura |
| Umbrales | `thresholds` nativos que hacen fallar la corrida: el resultado es un gate, no un PDF que nadie lee |
| Métricas | Exporta a Prometheus, que ya está en el stack aprobado del plan |
| Versionado | Los escenarios son código, viven en el repo y se revisan en un PR |

### Alternativas consideradas

**Gatling.** Muy potente y con excelentes reportes; Scala/Java encaja con el backend.
Descartada porque el DSL de Scala es una barrera de entrada real para un equipo chico, y
porque su integración en CI es más pesada.

**JMeter.** El más extendido y con más ejemplos disponibles. Descartado porque su modelo es
GUI-first y los planes en XML son prácticamente irrevisables en un diff — exactamente lo
contrario de lo que buscamos para cualquier artefacto versionado.

**Artillery.** También JS y muy simple de arrancar. Descartada frente a k6 por comunidad más
chica y por umbrales menos expresivos.

**Reusar Playwright.** Ya está en el proyecto y sería cero herramientas nuevas. Descartada
porque Playwright levanta un navegador por sesión: no escala a la concurrencia que una
prueba de carga necesita, y mediría el rendimiento del navegador, no el del backend.

## Diseño previsto

Ubicación: `appKine-api/load/` — el backend es lo que se mide.

```
load/
├── escenarios/
│   ├── agenda-consulta-slots.js
│   ├── agenda-reserva-turno.js
│   └── sesion-registro.js
├── lib/
│   ├── auth.js          # login + selección de contexto (DP-02)
│   └── datos.js         # generadores de datos SINTÉTICOS
└── README.md
```

### Reglas innegociables

1. **Nunca contra un entorno compartido** sin autorización explícita y ventana acordada.
   Una prueba de carga contra un entorno que otros usan es una denegación de servicio
   autoinfligida.
2. **Datos exclusivamente sintéticos.** Nunca datos reales de pacientes, ni siquiera
   anonimizados a ojo.
3. **Cada escenario ejercita el aislamiento por tenant**: si todas las peticiones usan la
   misma organización, el resultado no representa el uso real y esconde el costo de los
   índices con alcance tenant.
4. Los escenarios autentican como lo hace un usuario real: login, selección de contexto y
   token acotado. Saltear ese paso mide un sistema que no existe.

### Métricas y umbrales

Los SLO concretos se fijan en su etapa. La forma del umbral:

```js
export const options = {
  thresholds: {
    http_req_failed:   ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
  },
};
```

Interesa el **p95 y p99**, no el promedio: el promedio esconde exactamente los casos que
arruinan la experiencia.

## Pasos pendientes

| Paso | Etapa destino |
|---|---|
| Instalar k6 y crear `load/` con el primer escenario | Cuando exista M12 (agenda) |
| Fijar los SLO numéricos por operación | AKINE-09.03 (según el plan) |
| Job de CI en cadencia programada, contra entorno dedicado | AKINE-09.03 |
| Línea base y detección de regresión entre releases | AKINE-09.03 |
