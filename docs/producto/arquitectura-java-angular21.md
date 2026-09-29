# Estructura de arquitectura: Backend Java (Spring Boot) + Frontend Angular 21

> Documento de referencia. Agosto 2026 — Angular 21 (release del 20/11/2025).

---

## 1. Backend Java — Spring Boot con Maven

### 1.1 Layout estándar (package by layer)

```
mi-api/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/empresa/miapi/
    │   │   ├── MiApiApplication.java
    │   │   ├── config/            # SecurityConfig, OpenApiConfig, CorsConfig, beans
    │   │   ├── controller/        # @RestController — solo HTTP, sin lógica
    │   │   ├── service/           # interfaces + impl/ — lógica de negocio, @Transactional
    │   │   ├── repository/        # @Repository / JpaRepository
    │   │   ├── entity/            # @Entity — mapeo a la base
    │   │   ├── dto/               # request/ y response/ — nunca exponer la entity
    │   │   ├── mapper/            # MapStruct entity <-> dto
    │   │   └── exception/         # excepciones propias + @RestControllerAdvice
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml / application-prod.yml
    │       └── db/migration/      # Flyway o Liquibase
    └── test/java/...
```

**Flujo de dependencias:** `Controller → Service → Repository → BD`

Los DTO son lo único que cruza hacia afuera. Las entities nunca salen del service.

**Responsabilidad de cada capa:**

| Capa | Hace | No hace |
|---|---|---|
| Controller | Recibe request, valida formato (`@Valid`), devuelve response | Lógica de negocio, acceso a BD |
| Service | Reglas de negocio, orquestación, transacciones | Conocer HTTP (nada de `HttpServletRequest`) |
| Repository | Consultas a la base | Reglas de negocio |
| Entity | Mapeo tabla/objeto | Salir del backend hacia el cliente |

### 1.2 Alternativa: package by feature

Mejor para microservicios que crecen. En lugar de agrupar por capa, se agrupa por dominio y adentro se repiten las capas:

```
com.empresa.miapi/
├── vehiculo/
│   ├── VehiculoController.java
│   ├── VehiculoService.java
│   ├── VehiculoRepository.java
│   ├── Vehiculo.java
│   └── dto/
├── poliza/
│   └── (idem)
└── shared/
    ├── config/
    ├── exception/
    └── util/
```

**Ventajas:** cada paquete es autocontenido, se puede usar visibilidad `package-private` para las clases internas, y el acoplamiento entre dominios queda explícito. Para micros medianos o grandes escala mucho mejor que agrupar por capa.

---

## 2. Frontend Angular 21

### 2.1 Cambios que impactan directamente en la estructura

Antes de ver las carpetas, tres cambios que rompen la estructura "clásica" de v17/v18:

1. **Zoneless por defecto.** Los proyectos nuevos ya no incluyen `zone.js`. El change detection lo manejan los signals.
2. **`OnPush` pasa a ser el default.** Componentes que dependían del change detection implícito pueden dejar de actualizarse.
3. **Vitest reemplaza a Karma** como test runner por defecto (Karma y Jasmine siguen soportados).
4. **`HttpClient` viene incluido** en toda app nueva, sin configuración extra.
5. **Sin sufijo `.component`** en los nombres de archivo (cambio que arrancó en v20): `app.ts`, no `app.component.ts`.

### 2.2 Lo que genera `ng new`

```
mi-app/
├── angular.json
├── package.json
├── tsconfig.json
├── public/                   # assets estáticos (antes src/assets)
└── src/
    ├── main.ts
    ├── index.html
    ├── styles.css
    └── app/
        ├── app.ts            # componente raíz (antes app.component.ts)
        ├── app.html
        ├── app.css
        ├── app.config.ts     # providers (antes app.module.ts)
        └── app.routes.ts
```

### 2.3 Estructura escalable

```
src/app/
├── core/                     # singletons, se cargan una sola vez
│   ├── services/             # auth-service.ts, token-service.ts
│   ├── interceptors/         # auth-interceptor.ts, error-interceptor.ts
│   ├── guards/               # auth-guard.ts
│   └── models/               # interfaces compartidas
├── shared/                   # standalone reutilizables
│   ├── components/           # navbar/, spinner/, confirm-dialog/
│   ├── pipes/
│   └── directives/
├── features/                 # una carpeta por dominio, lazy loaded
│   ├── vehiculos/
│   │   ├── pages/            # vehiculo-list/, vehiculo-form/
│   │   ├── components/       # componentes solo de este feature
│   │   ├── services/         # vehiculo-service.ts
│   │   ├── models/           # vehiculo.model.ts
│   │   └── vehiculos.routes.ts
│   └── polizas/
├── app.config.ts
└── app.routes.ts
```

**Regla de oro:** `core` no importa de `features`. `features` no importa de otros `features`. Lo común va a `shared`.

### 2.4 `app.config.ts`

```typescript
import {
  ApplicationConfig,
  provideZonelessChangeDetection,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth-interceptor';
import { errorInterceptor } from './core/interceptors/error-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
  ],
};
```

### 2.5 `app.routes.ts` con lazy loading por feature

```typescript
import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'vehiculos', pathMatch: 'full' },
  {
    path: 'vehiculos',
    loadChildren: () =>
      import('./features/vehiculos/vehiculos.routes').then(m => m.VEHICULOS_ROUTES),
  },
  {
    path: '**',
    loadComponent: () =>
      import('./shared/components/not-found/not-found').then(m => m.NotFound),
  },
];
```

Y el `vehiculos.routes.ts` del feature:

```typescript
import { Routes } from '@angular/router';

export const VEHICULOS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/vehiculo-list/vehiculo-list').then(m => m.VehiculoList),
  },
  {
    path: 'nuevo',
    loadComponent: () => import('./pages/vehiculo-form/vehiculo-form').then(m => m.VehiculoForm),
  },
  {
    path: ':id',
    loadComponent: () => import('./pages/vehiculo-form/vehiculo-form').then(m => m.VehiculoForm),
  },
];
```

### 2.6 Service con signals

```typescript
import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Vehiculo } from '../models/vehiculo.model';

@Injectable({ providedIn: 'root' })
export class VehiculoService {
  private http = inject(HttpClient);
  private readonly url = '/api/vehiculos';

  readonly vehiculos = signal<Vehiculo[]>([]);
  readonly cargando = signal(false);

  listar(): void {
    this.cargando.set(true);
    this.http.get<Vehiculo[]>(this.url).subscribe({
      next: v => this.vehiculos.set(v),
      complete: () => this.cargando.set(false),
    });
  }
}
```

### 2.7 Nota sobre Signal Forms

Es la API nueva de v21 para formularios, basada en signals. **Sigue en developer preview**: no conviene usarla en producción todavía. Para lo crítico, seguir con Reactive Forms.

---

## 3. Correspondencia entre las dos capas

| Backend | Frontend | Contrato |
|---|---|---|
| `VehiculoController` | `VehiculoService` (Angular) | endpoints REST |
| `VehiculoResponseDTO` | `vehiculo.model.ts` (interface) | JSON de respuesta |
| `VehiculoRequestDTO` | payload del formulario | JSON de request |
| `@RestControllerAdvice` | `errorInterceptor` | formato de error unificado |

Conviene mantener el DTO del backend y la interface de TypeScript sincronizados. Si el proyecto crece, generar las interfaces desde el OpenAPI del backend (`openapi-generator`, `ng-openapi-gen`) evita el desfasaje manual.

---

## Referencias

- Angular v21 — anuncio oficial: https://blog.angular.dev/announcing-angular-v21-57946c34f14b
- Documentación Angular: https://angular.dev
- Spring Boot: https://docs.spring.io/spring-boot/index.html
