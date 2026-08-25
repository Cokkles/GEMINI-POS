[CmdletBinding()]
param(
    [Uri]$HelperUrl = 'http://127.0.0.1:47831',
    [string]$PwaOrigin = 'https://cokkles.github.io',
    [string]$ExpectedVersion = '0.2.0',
    [switch]$AllowDevelopment
)

$ErrorActionPreference = 'Stop'
$script:Failures = [System.Collections.Generic.List[string]]::new()
function Pass([string]$Message) { Write-Host "PASS  $Message" -ForegroundColor Green }
function Fail([string]$Message) { $script:Failures.Add($Message); Write-Host "FAIL  $Message" -ForegroundColor Red }
function GetJson([string]$Path) {
    $response = Invoke-WebRequest -Uri ([Uri]::new($HelperUrl, $Path)) -TimeoutSec 5 -UseBasicParsing
    if ($response.StatusCode -ne 200) { throw "HTTP $($response.StatusCode)" }
    return $response.Content | ConvertFrom-Json
}

if ($HelperUrl.Scheme -ne 'http' -or $HelperUrl.Host -notin @('127.0.0.1', 'localhost', '::1')) { throw 'HelperUrl must use HTTP loopback.' }

try {
    $health = GetJson '/api/v1/health'
    if ($health.status -eq 'AVAILABLE') { Pass 'helper health is AVAILABLE' } else { Fail 'helper health is not AVAILABLE' }
    if ($health.version -eq $ExpectedVersion) { Pass "helper version is $ExpectedVersion" } else { Fail "helper version $($health.version) does not match expected $ExpectedVersion" }
    if (-not [string]::IsNullOrWhiteSpace($health.instance_id)) { Pass 'helper process instance is identified' } else { Fail 'helper process instance is missing' }
} catch { Fail ('helper health request failed: ' + $_.Exception.Message) }

try {
    $live = GetJson '/api/v1/live'
    if ($live.status -eq 'ALIVE') { Pass 'process liveness is ALIVE' } else { Fail 'process liveness is not ALIVE' }
} catch { Fail ('liveness request failed: ' + $_.Exception.Message) }

try {
    $ready = GetJson '/api/v1/ready'
    if ($ready.status -eq 'READY') { Pass 'deployment readiness is READY' } else { Fail 'deployment readiness is not READY' }
} catch { Fail ('readiness request failed: ' + $_.Exception.Message) }

try {
    $setup = GetJson '/api/v1/setup/status'
    if ($setup.production_ready) { Pass 'production setup is ready' }
    elseif ($AllowDevelopment -and $setup.mode -eq 'DEVELOPMENT') { Pass 'development setup accepted for smoke validation' }
    else { Fail 'production setup is not ready; inspect the Connection checklist' }
} catch { Fail ('setup readiness request failed: ' + $_.Exception.Message) }

try {
    $capabilities = GetJson '/api/v1/capabilities'
    $requiredCapabilities = @('helper.health', 'helper.readiness', 'helper.auth', 'helper.setup', 'helper.activity', 'aegis.proxy', 'calendar.read')
    $missingCapabilities = @($requiredCapabilities | Where-Object { $_ -notin $capabilities.capabilities })
    if ($missingCapabilities.Count -eq 0) { Pass 'required helper capabilities are advertised' } else { Fail ('capabilities missing: ' + ($missingCapabilities -join ', ')) }
} catch { Fail ('capability request failed: ' + $_.Exception.Message) }

try {
    $headers = @{ Origin = $PwaOrigin; 'Access-Control-Request-Method' = 'GET'; 'Access-Control-Request-Headers' = 'X-GPOS-Session' }
    $cors = Invoke-WebRequest -Uri ([Uri]::new($HelperUrl, '/api/v1/aegis/dashboard')) -Method Options -Headers $headers -TimeoutSec 5 -UseBasicParsing
    $originOk = $cors.Headers['Access-Control-Allow-Origin'] -eq $PwaOrigin
    $sessionHeaderOk = [string]$cors.Headers['Access-Control-Allow-Headers'] -match '(?i)X-GPOS-Session'
    $credentialsOk = $cors.Headers['Access-Control-Allow-Credentials'] -eq 'true'
    if ($originOk -and $sessionHeaderOk -and $credentialsOk) { Pass 'GitHub Pages session preflight is exact and credentialed' } else { Fail 'GitHub Pages session preflight is incomplete' }
} catch { Fail ('PWA preflight failed: ' + $_.Exception.Message) }

try {
    $auth = GetJson '/api/v1/auth/status'
    if ($auth.authenticated) { Pass 'helper session is authenticated' }
    elseif ($AllowDevelopment) { Pass 'unauthenticated state accepted for smoke validation' }
    else { Fail 'helper session is not authenticated' }
} catch { Fail ('authentication status request failed: ' + $_.Exception.Message) }

try {
    $apiResponse = Invoke-WebRequest -Uri ([Uri]::new($HelperUrl, '/api/v1/auth/status')) -TimeoutSec 5 -UseBasicParsing
    if ([string]$apiResponse.Headers['Cache-Control'] -match '(?i)no-store') { Pass 'API responses disable caching' } else { Fail 'API response is missing no-store' }
} catch { Fail ('API cache-policy request failed: ' + $_.Exception.Message) }

try {
    $surface = Invoke-WebRequest -Uri $HelperUrl -TimeoutSec 5 -UseBasicParsing
    $csp = [string]$surface.Headers['Content-Security-Policy']
    if ($csp -match "frame-ancestors 'none'" -and $surface.Headers['X-Content-Type-Options'] -eq 'nosniff') { Pass 'control surface security policy is active' } else { Fail 'control surface security policy is incomplete' }
} catch { Fail ('control surface policy request failed: ' + $_.Exception.Message) }

if ($null -ne $health) {
    if ($health.upstream.apps_script -eq 'CONFIGURED') { Pass 'Apps Script upstream is configured' }
    elseif ($AllowDevelopment) { Pass 'unconfigured Apps Script accepted for smoke validation' }
    else { Fail 'Apps Script upstream is not configured' }
}

if ($script:Failures.Count -gt 0) {
    Write-Host "RESULT: FAILED ($($script:Failures.Count) gate(s))" -ForegroundColor Red
    exit 1
}
Write-Host 'RESULT: PASSED' -ForegroundColor Green

