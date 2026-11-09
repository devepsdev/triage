# Guarda las claves de Triage en el VPS (/etc/triage/api.env, 600 root) sin mostrarlas nunca.
# La clave de Mistral sale del .env local y el secreto de Turnstile se pega con entrada oculta.
# Viajan por la entrada estándar de ssh: no aparecen en la línea de órdenes ni en el historial.
#
# Uso, desde PowerShell:
#   powershell -ExecutionPolicy Bypass -File despliegue\guardar-claves.ps1
param(
    [string]$ArchivoEnv = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$Ssh = (Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'),
    [string]$Servidor = 'vps'
)
$ErrorActionPreference = 'Stop'

# Es pública: se envía a cada navegador que abre la web.
$Sitekey = '0x4AAAAAAF6VM6frer_iBH3m'

# Sin comillas dobles: Windows PowerShell las rompe al pasarlas a un programa externo.
$Remoto = "set -eu; umask 077; nuevo=`$(mktemp); trap 'rm -f `$nuevo' EXIT; tr -d '\r' > `$nuevo; " +
    "n=`$(grep -cE '^(MISTRAL_API_KEY|TURNSTILE_SECRET|TURNSTILE_SITEKEY)=.+' `$nuevo); " +
    "[ `$n = 3 ] || { echo 'No han llegado las tres variables; no se ha cambiado nada.' >&2; exit 3; }; " +
    "sudo install -d -m 700 /etc/triage; sudo touch /etc/triage/api.env; " +
    "sudo grep -vE '^(MISTRAL_API_KEY|TURNSTILE_SECRET|TURNSTILE_SITEKEY)=' /etc/triage/api.env >> `$nuevo || true; " +
    "sudo install -m 600 -o root -g root `$nuevo /etc/triage/api.env; sudo cut -d= -f1 /etc/triage/api.env"

function Leer-ClaveMistral {
    if (-not (Test-Path -LiteralPath $ArchivoEnv)) { throw "No encuentro el archivo $ArchivoEnv" }
    foreach ($linea in Get-Content -LiteralPath $ArchivoEnv) {
        if ($linea -match '^\s*(?:export\s+)?MISTRAL_API_KEY\s*=\s*(.*?)\s*$') {
            $valor = $Matches[1].Trim('"', "'")
            if ($valor) { return $valor }
        }
    }
    throw "En $ArchivoEnv no hay un valor para MISTRAL_API_KEY"
}

function Leer-SecretoTurnstile {
    $seguro = Read-Host 'Pega el secreto de Turnstile (no se ve al pegarlo) y pulsa Intro' -AsSecureString
    $puntero = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro)
    try {
        $valor = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($puntero).Trim()
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($puntero)
    }
    if (-not $valor) { throw 'No has pegado nada; no se ha guardado ninguna clave.' }
    if ($valor -eq $Sitekey) { throw 'Eso es la Site Key: hace falta la Secret Key, la otra del widget.' }
    if ($valor -notmatch '^0x') { throw 'Eso no parece un secreto de Turnstile (empiezan por 0x); no se ha guardado nada.' }
    return $valor
}

function Enviar-Claves([string]$Contenido) {
    $inicio = New-Object System.Diagnostics.ProcessStartInfo
    $inicio.FileName = $Ssh
    $inicio.Arguments = "-o BatchMode=yes $Servidor `"$Remoto`""
    $inicio.UseShellExecute = $false
    $inicio.RedirectStandardInput = $true
    $inicio.RedirectStandardOutput = $true
    $inicio.RedirectStandardError = $true

    $proceso = [System.Diagnostics.Process]::Start($inicio)
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($Contenido)
    $proceso.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $proceso.StandardInput.Close()
    $salida = $proceso.StandardOutput.ReadToEnd()
    $errores = $proceso.StandardError.ReadToEnd()
    $proceso.WaitForExit()
    if ($proceso.ExitCode -ne 0) { throw "El VPS ha rechazado la operación (código $($proceso.ExitCode)): $errores" }
    return $salida
}

$mistral = $null
$secreto = $null
try {
    $mistral = Leer-ClaveMistral
    $secreto = Leer-SecretoTurnstile
    $contenido = "MISTRAL_API_KEY=$mistral`nTURNSTILE_SECRET=$secreto`nTURNSTILE_SITEKEY=$Sitekey`n"
    $nombres = Enviar-Claves $contenido
    Write-Host ''
    Write-Host 'Claves guardadas en el VPS. Variables que hay ahora en /etc/triage/api.env:'
    Write-Host $nombres
} catch {
    Write-Host ''
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    $mistral = $null
    $secreto = $null
    $contenido = $null
}
