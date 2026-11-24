# Levanta el backend de desarrollo con la IA prendida.
#
#   .\ops\levantar.ps1              # base, correo y Ollama (con GPU si hay), modelo precargado, backend local+ia
#   .\ops\levantar.ps1 -SinBackend  # solo la infraestructura; el backend se arranca aparte
#   .\ops\levantar.ps1 -SinIa       # como siempre: perfil local, sin Ollama
#
# Con una placa NVIDIA (nvidia-smi responde) suma compose.gpu.yaml: Ollama corre en la GPU y cada
# respuesta tarda ~1,5 s en vez de ~24 s en CPU (medido el 11/11/2026 en una RTX 4050).
#
# Cada centro puede apagar cualquier funcion en Organizacion -> Inteligencia artificial.
[CmdletBinding()]
param(
	[switch]$SinBackend,
	[switch]$SinIa,
	[string]$Modelo = $(if ($env:AKINE_ASISTENTE_MODELO) { $env:AKINE_ASISTENTE_MODELO } else { 'qwen3:4b-instruct' }),
	[string]$OllamaUrl = 'http://127.0.0.1:11434'  # no localhost: PowerShell 5.1 prueba ::1 primero y Ollama escucha solo en 127.0.0.1
)
# 'Continue' y no 'Stop': en PowerShell 5.1, con la salida capturada (launch.json, redireccion), el
# progreso que docker y ollama escriben por stderr se vuelve un error y cortaria el script aunque el
# comando haya terminado bien. Los comandos nativos se juzgan por $LASTEXITCODE; los cmdlets, con -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$apagarBiblioteca = $false
function Escribir-Paso([string]$texto) { Write-Host "==> $texto" -ForegroundColor Cyan }

# 1. Contenedores
$archivos = @('-f', 'compose.yaml')
$servicios = @('postgres', 'mailpit')
if (-not $SinIa) {
	$servicios += 'ollama'
	$gpu = $false
	try { $null = & nvidia-smi -L 2>$null; $gpu = ($LASTEXITCODE -eq 0) } catch { $gpu = $false }
	if ($gpu) {
		$archivos += @('-f', 'compose.gpu.yaml')
		Escribir-Paso 'Placa NVIDIA detectada: Ollama con GPU'
	} else {
		Write-Warning 'Sin GPU NVIDIA: Ollama corre en CPU y cada respuesta tarda bastante mas.'
	}
}
# Los contenedores tienen nombre fijo (akine-postgres, akine-mailpit, akine-ollama) y pueden existir
# ya, creados desde otro checkout o worktree: para compose son de otro proyecto y `up` choca por el
# nombre. Si existe se arranca tal cual; solo se crea con compose el que falta.
$faltan = @()
foreach ($servicio in $servicios) {
	$nombre = "akine-$servicio"
	$existe = & docker ps -a --filter "name=^/$nombre$" --format '{{.Names}}'
	if ($existe) {
		& docker start $nombre | Out-Null
		if ($servicio -eq 'ollama' -and $gpu) {
			$conGpu = & docker inspect $nombre --format '{{json .HostConfig.DeviceRequests}}'
			if ($conGpu -notmatch 'gpu') {
				Write-Warning "$nombre existe sin GPU. Para recrearlo con la placa: docker rm -f $nombre y volver a correr este script."
			}
		}
		Escribir-Paso "$nombre ya existia: arrancado"
	} else {
		$faltan += $servicio
	}
}
if ($faltan.Count -gt 0) {
	Escribir-Paso "docker compose up -d $($faltan -join ' ')"
	& docker compose @archivos up -d @faltan
	if ($LASTEXITCODE -ne 0) { throw 'docker compose fallo. Docker Desktop esta arrancado?' }
}

if (-not $SinIa) {
	# 2. Ollama responde
	Escribir-Paso "Esperando a Ollama en $OllamaUrl"
	$listo = $false
	foreach ($i in 1..30) {
		try { $null = Invoke-RestMethod "$OllamaUrl/api/tags" -TimeoutSec 2 -ErrorAction Stop; $listo = $true; break } catch { Start-Sleep -Seconds 1 }
	}
	if (-not $listo) { throw "Ollama no respondio en $OllamaUrl" }

	# 3. Modelo descargado
	$tags = Invoke-RestMethod "$OllamaUrl/api/tags"
	$buscado = if ($Modelo.Contains(':')) { $Modelo } else { "$($Modelo):latest" }
	if (-not ($tags.models | Where-Object { $_.name -eq $buscado })) {
		Escribir-Paso "Bajando $Modelo (una sola vez, unos GB)"
		# docker exec y no compose exec: el contenedor puede ser de otro proyecto de compose (ver arriba).
		& docker exec akine-ollama ollama pull $Modelo
		if ($LASTEXITCODE -ne 0) { throw "No se pudo bajar $Modelo" }
	}

	# 4. Precarga: deja el modelo en memoria para que la primera pregunta no pague la carga
	Escribir-Paso "Precargando $Modelo"
	# Mismo num_ctx que las llamadas reales (OpcionesGeneracion.NUM_CTX_POR_DEFECTO): con otro, Ollama recarga el modelo en la primera pregunta.
	$cuerpo = @{ model = $Modelo; messages = @(); keep_alive = '2h'; options = @{ num_ctx = 8192 } } | ConvertTo-Json
	try {
		$null = Invoke-RestMethod "$OllamaUrl/api/chat" -Method Post -Body $cuerpo -ContentType 'application/json' -TimeoutSec 120 -ErrorAction Stop
		$ps = Invoke-RestMethod "$OllamaUrl/api/ps" -ErrorAction Stop
		foreach ($m in $ps.models) {
			$en = if ($m.size_vram -ge $m.size) { 'GPU' } elseif ($m.size_vram -gt 0) { 'GPU y CPU' } else { 'CPU' }
			Escribir-Paso ("{0} cargado en {1} ({2:N0} MB)" -f $m.name, $en, ($m.size / 1MB))
		}
	} catch {
		Write-Warning "No se pudo precargar el modelo: $($_.Exception.Message). La primera pregunta va a tardar."
	}

	# 5. Embeddings de la biblioteca
	$onnx = if ($env:AKINE_BIBLIOTECA_MODELO_RUTAMODELO) { $env:AKINE_BIBLIOTECA_MODELO_RUTAMODELO } else { Join-Path $repo '..\modelos\e5-base\model.onnx' }
	if (-not (Test-Path $onnx)) {
		Write-Warning "No esta el modelo de embeddings en $onnx. La biblioteca queda apagada en este arranque."
		$apagarBiblioteca = $true
	}

	# 6. Key de Anthropic para el agente del asistente (proveedor claude en el perfil ia). Se hereda por
	# el entorno; nunca se muestra.
	if (-not $env:AKINE_ANTHROPIC_API_KEY) {
		Write-Warning 'Falta AKINE_ANTHROPIC_API_KEY: el asistente responde con el alternativo (Ollama, compatible), mas lento y menos preciso.'
	}
}

if ($SinBackend) { Escribir-Paso 'Infraestructura lista. Backend: ./mvnw spring-boot:run "-Dspring-boot.run.profiles=local,ia"'; return }

# 7. Backend
$perfiles = if ($SinIa) { 'local' } else { 'local,ia' }
Escribir-Paso "Backend con perfiles $perfiles"
# La biblioteca se apaga solo para este backend: la variable se restaura al salir, para que un
# arranque siguiente en la misma consola (ya con el ONNX bajado) no la herede apagada.
$antes = $env:AKINE_BIBLIOTECA_ENABLED
if ($apagarBiblioteca) { $env:AKINE_BIBLIOTECA_ENABLED = 'false' }
try {
	& .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=$perfiles"
} finally {
	$env:AKINE_BIBLIOTECA_ENABLED = $antes
}
