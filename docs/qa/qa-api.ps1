# Ayudas de PowerShell para el QA manual de AKINE (G-12). Ver docs/qa/README.md §5.
#
#   . .\docs\qa\qa-api.ps1                      # cargar en la sesion (punto y espacio adelante)
#   $env:QA_API = 'http://localhost:8080'       # opcional: otro backend (default 8080)
#   $T = Entrar 'qa.admin-a@ejemplo.test'       # pide la contrasena sin mostrarla; token con contexto
#   $T = Entrar 'qa.admin-a@ejemplo.test' 12    # contexto de la organizacion 12 (cuenta en dos orgs)
#   Api GET '/api/v1/personas?size=5' $T        # -> Status, Tipo (problem type) y Cuerpo
#   Api POST '/api/v1/personas' $T @{ nombre = 'Ana'; apellido = 'Prueba' }
#
# Sirve para las operaciones que NO tienen pantalla en appKine-web (historia clinica, casos, planes,
# egresos, soporte de plataforma...) y para probar aislamiento y permisos con otro token. Funciona
# en Windows PowerShell 5.1 y en PowerShell 7. No guarda nada en disco: el token vive en la variable
# de la sesion y vence a los 10 minutos (volver a llamar a Entrar).

function Get-QaApi {
	if ($env:QA_API) { return $env:QA_API.TrimEnd('/') }
	return 'http://localhost:8080'
}

function ConvertTo-QaJson($cuerpo) {
	if ($null -eq $cuerpo) { return $null }
	if ($cuerpo -is [string]) { return $cuerpo }
	return ($cuerpo | ConvertTo-Json -Depth 10 -Compress)
}

function Api {
	param(
		[Parameter(Mandatory = $true)][string] $Metodo,
		[Parameter(Mandatory = $true)][string] $Ruta,
		[string] $Token,
		$Cuerpo,
		[hashtable] $Headers = @{}
	)
	$h = @{ Accept = 'application/json, application/problem+json' }
	foreach ($k in $Headers.Keys) { $h[$k] = $Headers[$k] }
	if ($Token) { $h['Authorization'] = "Bearer $Token" }
	$parametros = @{ Method = $Metodo; Uri = "$(Get-QaApi)$Ruta"; Headers = $h; UseBasicParsing = $true }
	$json = ConvertTo-QaJson $Cuerpo
	if ($null -ne $json) {
		$parametros['ContentType'] = 'application/json; charset=utf-8'
		$parametros['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
	}
	$status = 0
	$texto = ''
	try {
		$r = Invoke-WebRequest @parametros
		$status = [int] $r.StatusCode
		# El backend responde UTF-8 sin charset en el Content-Type, y Windows PowerShell 5.1 lo
		# decodificaria como ISO-8859-1 ("Ana" con tilde saldria "Ãna"): se decodifican los bytes.
		$texto = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
	} catch {
		$resp = $_.Exception.Response
		if ($null -eq $resp) { throw }
		$status = [int] $resp.StatusCode
		if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
			$texto = $_.ErrorDetails.Message
		} else {
			try {
				$lector = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
				$texto = $lector.ReadToEnd()
			} catch { $texto = '' }
		}
	}
	$objeto = $null
	if ($texto) { try { $objeto = $texto | ConvertFrom-Json } catch { $objeto = $texto } }
	$tipo = $null
	if ($objeto -and $objeto.PSObject.Properties['type']) { $tipo = ($objeto.type -split '/')[-1] }
	[pscustomobject]@{ Status = $status; Tipo = $tipo; Cuerpo = $objeto }
}

function Entrar {
	param(
		[Parameter(Mandatory = $true)][string] $Email,
		[long] $OrganizationId = 0,
		[string] $Password
	)
	if (-not $Password) {
		$seguro = Read-Host -AsSecureString "Contrasena de $Email"
		$Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
			[Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))
	}
	$login = Api POST '/api/v1/auth/login' $null @{ email = $Email; password = $Password }
	if ($login.Status -ne 200) { throw "login de $Email -> HTTP $($login.Status) $($login.Tipo)" }
	$pre = $login.Cuerpo.accessToken
	$contextos = @((Api GET '/api/v1/me/contexts' $pre).Cuerpo)
	if ($OrganizationId -gt 0) {
		$c = $contextos | Where-Object { $_.organizationId -eq $OrganizationId } | Select-Object -First 1
	} else {
		$c = $contextos | Select-Object -First 1
	}
	if ($null -eq $c) {
		Write-Warning "$Email no tiene contexto$(if ($OrganizationId) { " en la organizacion $OrganizationId" }): se devuelve el token sin contexto (pre_context)"
		return $pre
	}
	$cuerpo = @{ organizationId = $c.organizationId }
	if ($c.consultorioId) { $cuerpo['consultorioId'] = $c.consultorioId }
	$ctx = Api POST '/api/v1/auth/context' $pre $cuerpo
	if ($ctx.Status -ne 200) { throw "contexto de $Email -> HTTP $($ctx.Status) $($ctx.Tipo)" }
	Write-Host "contexto: organizacion $($c.organizationId), sede $($c.consultorioId)"
	return $ctx.Cuerpo.accessToken
}

# Clave nueva para los endpoints que piden Idempotency-Key o idempotencyKey.
function Nueva-Clave { [guid]::NewGuid().ToString() }
