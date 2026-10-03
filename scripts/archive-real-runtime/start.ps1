param(
  [Parameter(Mandatory=$true)][string]$RunRoot,
  [Parameter(Mandatory=$true)][string]$HostAddress,
  [int]$Port = 18121,
  [int]$ClientRelayPort = 18123,
  [string]$WslDistribution = 'docker-desktop',
  [string]$ChrootRoot = '/dev/shm/cyf-aam-m6-test-20260930',
  [string]$LinuxRunName = 'archive-real-runtime',
  [string]$LinuxJava = '/opt/aam-java25/bin/java',
  [string]$WslWindowsMount = '/mnt/host'
)
$ErrorActionPreference = 'Stop'

function Require-Environment([string]$Name) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value) -or $value -ne $value.Trim()) { throw "$Name is required" }
  return $value
}
function Write-Utf8LfFile([string]$Path, [string]$Content) {
  $normalized = $Content.Replace("`r`n", "`n").Replace("`r", "`n")
  if (-not $normalized.EndsWith("`n", [StringComparison]::Ordinal)) { $normalized += "`n" }
  [IO.File]::WriteAllText($Path, $normalized, [Text.UTF8Encoding]::new($false))
}
function Assert-PrivateAddress([string]$Value, [string]$Name) {
  $ip = $null
  if (-not [Net.IPAddress]::TryParse($Value, [ref]$ip)) { throw "$Name must be an explicit IP address" }
  if ($ip.Equals([Net.IPAddress]::Any) -or $ip.Equals([Net.IPAddress]::IPv6Any)) { throw "$Name cannot be a wildcard" }
  $bytes = $ip.GetAddressBytes()
  $private = [Net.IPAddress]::IsLoopback($ip) -or
    ($ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and (
      $bytes[0] -eq 10 -or
      ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
      ($bytes[0] -eq 192 -and $bytes[1] -eq 168)))
  if (-not $private) { throw "$Name must be loopback or RFC1918 private" }
}
function Assert-MySqlJdbcUrl([string]$Value, [string]$ExpectedDatabase) {
  if (-not $Value.StartsWith('jdbc:mysql://', [StringComparison]::Ordinal)) {
    throw 'CYF_H02_MYSQL_URL must use jdbc:mysql with one explicit endpoint'
  }
  $uri = $null
  if (-not [Uri]::TryCreate($Value.Substring(5), [UriKind]::Absolute, [ref]$uri) -or
      $uri.Scheme -cne 'mysql' -or
      -not [string]::IsNullOrEmpty($uri.UserInfo) -or
      -not [string]::IsNullOrEmpty($uri.Query) -or
      -not [string]::IsNullOrEmpty($uri.Fragment) -or
      $uri.Port -lt 1 -or $uri.Port -gt 65535 -or
      $uri.Authority.Contains(',')) {
    throw 'CYF_H02_MYSQL_URL must name one endpoint without credentials or options'
  }
  $databaseMatch = [regex]::Match($uri.AbsolutePath, '^/([A-Za-z0-9_]{1,64})$')
  if (-not $databaseMatch.Success -or $databaseMatch.Groups[1].Value -cne $ExpectedDatabase) {
    throw 'CYF_H02_MYSQL_URL must name exactly the confirmed fixture database'
  }
  Assert-PrivateAddress $uri.DnsSafeHost 'CYF_H02_MYSQL_URL'
}
function Normalize-MySqlDataDirectory([string]$Value) {
  $normalized = $Value.Replace('\','/').TrimEnd('/')
  if ($normalized.Contains('//') -or
      ($normalized -notmatch '^[A-Za-z]:/[^/].*$' -and $normalized -notmatch '^/[^/].*$')) {
    throw 'CYF_FIXTURE_MYSQL_DATA_DIRECTORY must be one absolute data directory'
  }
  foreach ($segment in $normalized.Split('/')) {
    if ($segment -eq '.' -or $segment -eq '..') {
      throw 'CYF_FIXTURE_MYSQL_DATA_DIRECTORY cannot contain relative path segments'
    }
  }
  return $normalized
}

function Convert-ToWslHostPath([string]$WindowsPath) {
  $full = [IO.Path]::GetFullPath($WindowsPath)
  if ($full -notmatch '^([A-Za-z]):\\(.*)$') { throw 'RunRoot must be a drive-rooted Windows path' }
  $drive = $Matches[1].ToLowerInvariant()
  $tail = $Matches[2].Replace('\','/')
  return "$($WslWindowsMount.TrimEnd('/'))/$drive/$tail"
}
function Assert-SafeShellLiteral([string]$Value, [string]$Name) {
  if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9._/:\\-]+$') {
    throw "$Name contains characters that are unsafe for the generated shell launcher"
  }
}
function Assert-PrivateHttpOrigin([string]$Value, [string]$Name) {
  $uri = $null
  if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
      @('http','https') -notcontains $uri.Scheme -or
      -not [string]::IsNullOrEmpty($uri.UserInfo) -or
      $uri.AbsolutePath -ne '/' -or
      -not [string]::IsNullOrEmpty($uri.Query) -or
      -not [string]::IsNullOrEmpty($uri.Fragment)) {
    throw "$Name must be an HTTP(S) origin without credentials, path, query or fragment"
  }
  if ($uri.Host -eq 'localhost') { return }
  Assert-PrivateAddress $uri.Host $Name
}

Assert-PrivateAddress $HostAddress 'HostAddress'
Assert-PrivateAddress (Require-Environment 'CYF_REDIS_HOST') 'CYF_REDIS_HOST'
Assert-PrivateAddress (Require-Environment 'CYF_RABBIT_HOST') 'CYF_RABBIT_HOST'
Assert-PrivateHttpOrigin (Require-Environment 'CYF_FIXTURE_WEB_ORIGIN') 'CYF_FIXTURE_WEB_ORIGIN'
if ($Port -lt 1 -or $Port -gt 65535 -or $ClientRelayPort -lt 1 -or $ClientRelayPort -gt 65535 -or $Port -eq $ClientRelayPort) {
  throw 'Fixture and client relay ports must be distinct valid TCP ports'
}
$redisPortValue = Require-Environment 'CYF_REDIS_PORT'
$redisPort = 0
if (-not [int]::TryParse($redisPortValue, [ref]$redisPort) -or $redisPort -lt 1 -or $redisPort -gt 65535) {
  throw 'CYF_REDIS_PORT must be an integer in the TCP port range'
}
$rabbitPortValue = Require-Environment 'CYF_RABBIT_PORT'
$rabbitPort = 0
if (-not [int]::TryParse($rabbitPortValue, [ref]$rabbitPort) -or $rabbitPort -lt 1 -or $rabbitPort -gt 65535) {
  throw 'CYF_RABBIT_PORT must be an integer in the TCP port range'
}
if ($LinuxRunName -notmatch '^[a-z0-9][a-z0-9-]{0,63}$') { throw 'LinuxRunName is invalid' }
if ($WslDistribution -notmatch '^[A-Za-z0-9._-]{1,64}$') { throw 'WslDistribution is invalid' }
if ($ChrootRoot -notmatch '^/[A-Za-z0-9._/-]+$' -or $LinuxJava -notmatch '^/[A-Za-z0-9._/-]+$') {
  throw 'ChrootRoot and LinuxJava must be absolute safe Linux paths'
}
Assert-SafeShellLiteral $WslWindowsMount 'WslWindowsMount'

$required = @(
  'CYF_H02_MYSQL_ISOLATED','CYF_H02_MYSQL_URL','CYF_H02_MYSQL_DATABASE_CONFIRM',
  'CYF_H02_MYSQL_USER','CYF_H02_MYSQL_PASSWORD',
  'CYF_FIXTURE_MYSQL_SERVER_PORT','CYF_FIXTURE_MYSQL_DATA_DIRECTORY','CYF_FIXTURE_WEB_ORIGIN',
  'CYF_FIXTURE_JWT_ISSUER','CYF_FIXTURE_JWT_AUDIENCE','CYF_FIXTURE_ACTOR',
  'CYF_FIXTURE_OWNER','CYF_FIXTURE_CLIENT','CYF_FIXTURE_AGENT_ID',
  'CYF_FIXTURE_API_KEY','CYF_FIXTURE_API_KEY_ID','CYF_REDIS_HOST','CYF_REDIS_PORT','CYF_REDIS_PASSWORD',
  'CYF_RABBIT_HOST','CYF_RABBIT_PORT',
  'CYF_RABBIT_VHOST','CYF_RABBIT_USERNAME','CYF_RABBIT_PASSWORD'
)
foreach ($name in $required) { [void](Require-Environment $name) }
if ((Require-Environment 'CYF_H02_MYSQL_ISOLATED') -ne 'true') { throw 'CYF_H02_MYSQL_ISOLATED must be true' }
$mysqlDatabase = Require-Environment 'CYF_H02_MYSQL_DATABASE_CONFIRM'
if ($mysqlDatabase -notmatch '^[A-Za-z0-9_]{1,64}$') { throw 'CYF_H02_MYSQL_DATABASE_CONFIRM is invalid' }
$mysqlUser = Require-Environment 'CYF_H02_MYSQL_USER'
if ($mysqlUser -notmatch '^[A-Za-z0-9_.-]{1,64}$' -or $mysqlUser.Equals('root', [StringComparison]::OrdinalIgnoreCase)) {
  throw 'CYF_H02_MYSQL_USER must be an explicit non-root fixture account'
}
$mysqlServerPortValue = Require-Environment 'CYF_FIXTURE_MYSQL_SERVER_PORT'
$mysqlServerPort = 0
if (-not [int]::TryParse($mysqlServerPortValue, [ref]$mysqlServerPort) -or
    $mysqlServerPort -lt 1 -or $mysqlServerPort -gt 65535) {
  throw 'CYF_FIXTURE_MYSQL_SERVER_PORT must be an integer in the TCP port range'
}
[void](Normalize-MySqlDataDirectory (Require-Environment 'CYF_FIXTURE_MYSQL_DATA_DIRECTORY'))
Assert-MySqlJdbcUrl (Require-Environment 'CYF_H02_MYSQL_URL') $mysqlDatabase
if ((Require-Environment 'CYF_RABBIT_VHOST') -eq '/') { throw 'A dedicated Rabbit vhost is required' }

$run = [IO.Path]::GetFullPath($RunRoot)
$sourceWsl = Convert-ToWslHostPath $run
Assert-SafeShellLiteral $sourceWsl 'RunRoot WSL path'
$control = Join-Path $run 'control'
$bundle = Join-Path $control 'bundle'
$metadataFile = Join-Path $control 'launcher.json'
if (Test-Path -LiteralPath $metadataFile) { throw 'Fixture launcher metadata already exists; stop the prior run first' }
New-Item -ItemType Directory -Force -Path $control | Out-Null
if (Test-Path -LiteralPath $bundle) { throw 'Fresh runtime bundle directory required' }
New-Item -ItemType Directory -Path $bundle | Out-Null

$api = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$classpathFile = Join-Path $control 'runtime-classpath-windows.txt'
$compileLog = Join-Path $control 'classpath-export.log'
$compileError = Join-Path $control 'classpath-export.stderr.log'
$env:CYF_RUNTIME_CLASSPATH_FILE = $classpathFile
$lock = $null
while (-not $lock) {
  try { $lock = [IO.File]::Open('C:\tmp\cyf-gradle.lock','OpenOrCreate','ReadWrite','None') }
  catch [IO.IOException] { Start-Sleep -Seconds 2 }
}
try {
  Push-Location $api
  try {
    & .\gradlew.bat :chat:jia-chat-starter:archiveRealRuntimeClasspath --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Xss256k' 1> $compileLog 2> $compileError
    if ($LASTEXITCODE -ne 0) { throw "archiveRealRuntimeClasspath failed; inspect $compileLog and $compileError" }
  } finally { Pop-Location }
} finally { $lock.Dispose() }

$entries = @(Get-Content -LiteralPath $classpathFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($entries.Count -eq 0) { throw 'Exported runtime classpath is empty' }
$linuxRunRoot = "/fixtures/$LinuxRunName"
$linuxEntries = [Collections.Generic.List[string]]::new()
$manifest = [Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $entries.Count; $index++) {
  $source = [IO.Path]::GetFullPath($entries[$index])
  if (-not (Test-Path -LiteralPath $source)) { throw "Runtime classpath entry is missing: $source" }
  $slot = '{0:D4}' -f $index
  $destination = Join-Path $bundle $slot
  $item = Get-Item -LiteralPath $source
  if ($item.PSIsContainer) {
    New-Item -ItemType Directory -Path $destination | Out-Null
    Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $destination -Recurse -Force
    $linuxEntries.Add("$linuxRunRoot/bundle/$slot")
    $manifest.Add([ordered]@{ entry=$slot; kind='directory' })
  } else {
    New-Item -ItemType Directory -Path $destination | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $linuxEntries.Add("$linuxRunRoot/bundle/$slot/$($item.Name)")
    $manifest.Add([ordered]@{ entry="$slot/$($item.Name)"; kind='file'; bytes=$item.Length })
  }
}
[IO.File]::WriteAllText((Join-Path $control 'runtime-classpath-linux.txt'), ($linuxEntries -join ':') + "`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $control 'bundle-manifest.json'), ($manifest | ConvertTo-Json -Depth 4) + "`n", [Text.UTF8Encoding]::new($false))

$relaySource = @"
import net from 'node:net'
const [listenText, targetHost, targetText] = process.argv.slice(2)
const listenPort = Number.parseInt(listenText, 10)
const targetPort = Number.parseInt(targetText, 10)
if (!Number.isInteger(listenPort) || !Number.isInteger(targetPort) || !targetHost) process.exit(2)
const sockets = new Set()
const server = net.createServer(source => {
  const target = net.connect(targetPort, targetHost)
  sockets.add(source); sockets.add(target)
  source.on('error', () => target.destroy()); target.on('error', () => source.destroy())
  source.on('close', () => { sockets.delete(source); target.destroy() })
  target.on('close', () => { sockets.delete(target); source.destroy() })
  source.pipe(target); target.pipe(source)
})
const shutdown = () => { for (const socket of sockets) socket.destroy(); server.close(() => process.exit(0)) }
process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown)
server.listen(listenPort, '127.0.0.1')
"@
Write-Utf8LfFile (Join-Path $control 'tcp-relay.mjs') $relaySource
$clientScriptSource = Join-Path $PSScriptRoot 'run-client.sh'
Write-Utf8LfFile (Join-Path $control 'run-client.sh') ([IO.File]::ReadAllText($clientScriptSource))

$sourceWsl = Convert-ToWslHostPath $control
Assert-SafeShellLiteral $sourceWsl 'control path'
Assert-SafeShellLiteral $linuxRunRoot 'Linux runtime path'
$launchTemplate = @'
#!/bin/sh
set -eu
umask 077
root='__CHROOT__'
source_root='__SOURCE__'
run='__RUN__'
java='__JAVA__'
server_host='__HOST__'
server_port='__PORT__'
relay_port='__RELAY_PORT__'
java_pid=''
relay_pid=''
cleanup() {
  [ -z "$java_pid" ] || kill "$java_pid" 2>/dev/null || true
  [ -z "$relay_pid" ] || kill "$relay_pid" 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM
[ -f "$root/etc/alpine-release" ]
[ -x "$root$java" ]
[ -x "$root/usr/bin/node" ]
[ -x "$root/usr/bin/setsid" ]
[ -x "$root/usr/bin/nohup" ]
[ -f "$source_root/runtime-classpath-linux.txt" ]
[ ! -e "$root$run" ] || { echo 'fresh Linux runtime path required' >&2; exit 1; }
mkdir -p "$root$run/bundle" "$root$run/io" "$root$run/artifacts"
chmod 700 "$root$run" "$root$run/io" "$root$run/artifacts"
cp -a "$source_root/bundle/." "$root$run/bundle/"
cp "$source_root/runtime-classpath-linux.txt" "$root$run/runtime-classpath-linux.txt"
cp "$source_root/tcp-relay.mjs" "$root$run/tcp-relay.mjs"
cp "$source_root/run-client.sh" "$root$run/run-client.sh"
chmod 600 "$root$run/runtime-classpath-linux.txt" "$root$run/tcp-relay.mjs"
chmod 700 "$root$run/run-client.sh"
export CYF_FIXTURE_HOST="$server_host"
export CYF_FIXTURE_PORT="$server_port"
export CYF_FIXTURE_READY_FILE="$run/io/ready.json"
export CYF_FIXTURE_RESULT_FILE="$run/io/result.json"
export CYF_FIXTURE_ADMIN_TOKEN_FILE="$run/io/admin.jwt"
export CYF_FIXTURE_ARTIFACT_ROOT="$run/artifacts"
cp_value=$(cat "$root$run/runtime-classpath-linux.txt")
chroot "$root" /usr/bin/setsid /usr/bin/nohup /usr/bin/node "$run/tcp-relay.mjs" "$relay_port" "$server_host" "$server_port" </dev/null >>"$root$run/io/relay.stdout.log" 2>>"$root$run/io/relay.stderr.log" &
relay_pid=$!
chroot "$root" /usr/bin/setsid /usr/bin/nohup "$java" -Xmx384m -XX:MaxMetaspaceSize=224m -Xss256k -cp "$cp_value" cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication </dev/null >>"$root$run/io/java.stdout.log" 2>>"$root$run/io/java.stderr.log" &
java_pid=$!
sleep 1
java_cmd=$(tr '\000' ' ' < "/proc/$java_pid/cmdline" 2>/dev/null || true)
relay_cmd=$(tr '\000' ' ' < "/proc/$relay_pid/cmdline" 2>/dev/null || true)
java_exe=$(readlink -f "/proc/$java_pid/exe" 2>/dev/null || true)
relay_exe=$(readlink -f "/proc/$relay_pid/exe" 2>/dev/null || true)
expected_java=$(readlink -f "$root$java")
expected_node=$(readlink -f "$root/usr/bin/node")
[ "$java_exe" = "$expected_java" ] || { echo 'Java PID is not the fixture JVM' >&2; exit 1; }
[ "$relay_exe" = "$expected_node" ] || { echo 'Relay PID is not the Node relay' >&2; exit 1; }
case "$java_cmd" in *cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication*) ;; *) echo 'Java fixture failed to start' >&2; exit 1;; esac
case "$relay_cmd" in *"$run/tcp-relay.mjs"*) ;; *) echo 'Client relay failed to start' >&2; exit 1;; esac
printf '{"javaPid":%s,"relayPid":%s,"mainClass":"cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication","linuxRunRoot":"%s","clientWsUrl":"ws://127.0.0.1:%s/ws/agent/channel","browserApiOrigin":"http://%s:%s"}\n' "$java_pid" "$relay_pid" "$run" "$relay_port" "$server_host" "$server_port" > "$root$run/io/process.json"
trap - EXIT HUP INT TERM
echo STARTED
'@
$launch = $launchTemplate.Replace('__CHROOT__', $ChrootRoot).
  Replace('__SOURCE__', $sourceWsl).
  Replace('__RUN__', $linuxRunRoot).
  Replace('__JAVA__', $LinuxJava).
  Replace('__HOST__', $HostAddress).
  Replace('__PORT__', [string]$Port).
  Replace('__RELAY_PORT__', [string]$ClientRelayPort)
$launchFile = Join-Path $control 'launch.sh'
Write-Utf8LfFile $launchFile $launch

$verifyTemplate = @'
#!/bin/sh
set -eu
root='__CHROOT__'
run='__RUN__'
java='__JAVA__'
process="$root$run/io/process.json"
sleep 1
[ -f "$process" ] || { echo 'Fixture process metadata disappeared after launcher exit' >&2; exit 1; }
java_pid=$(chroot "$root" /usr/bin/node -p "require('$run/io/process.json').javaPid")
relay_pid=$(chroot "$root" /usr/bin/node -p "require('$run/io/process.json').relayPid")
case "$java_pid" in ''|*[!0-9]*) echo 'Invalid Java PID' >&2; exit 1;; esac
case "$relay_pid" in ''|*[!0-9]*) echo 'Invalid relay PID' >&2; exit 1;; esac
java_cmd=$(tr '\000' ' ' < "/proc/$java_pid/cmdline" 2>/dev/null || true)
relay_cmd=$(tr '\000' ' ' < "/proc/$relay_pid/cmdline" 2>/dev/null || true)
java_exe=$(readlink -f "/proc/$java_pid/exe" 2>/dev/null || true)
relay_exe=$(readlink -f "/proc/$relay_pid/exe" 2>/dev/null || true)
expected_java=$(readlink -f "$root$java")
expected_node=$(readlink -f "$root/usr/bin/node")
java_valid=0
relay_valid=0
[ "$java_exe" = "$expected_java" ] && case "$java_cmd" in *cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication*) java_valid=1;; esac
[ "$relay_exe" = "$expected_node" ] && case "$relay_cmd" in *"$run/tcp-relay.mjs"*) relay_valid=1;; esac
if [ "$java_valid" -eq 1 ] && [ "$relay_valid" -eq 1 ]; then
  echo VERIFIED
  exit 0
fi
[ "$java_valid" -eq 0 ] || kill -TERM "$java_pid" 2>/dev/null || true
[ "$relay_valid" -eq 0 ] || kill -TERM "$relay_pid" 2>/dev/null || true
echo 'Detached fixture processes did not survive launcher exit with exact identities' >&2
exit 1
'@
$verify = $verifyTemplate.Replace('__CHROOT__', $ChrootRoot).Replace('__RUN__', $linuxRunRoot).Replace('__JAVA__', $LinuxJava)
$verifyFile = Join-Path $control 'verify-running.sh'
Write-Utf8LfFile $verifyFile $verify

$oldWslEnv = $env:WSLENV
$existing = @($oldWslEnv -split ':' | Where-Object { $_ })
$env:WSLENV = (@($existing + $required) | Select-Object -Unique) -join ':'
try {
  $launchWsl = Convert-ToWslHostPath $launchFile
  & wsl.exe -d $WslDistribution -- /bin/sh $launchWsl
  if ($LASTEXITCODE -ne 0) { throw 'Linux chroot fixture launch failed' }
  $verifyWsl = Convert-ToWslHostPath $verifyFile
  & wsl.exe -d $WslDistribution -- /bin/sh $verifyWsl
  if ($LASTEXITCODE -ne 0) { throw 'Detached Linux fixture process verification failed' }
} finally { $env:WSLENV = $oldWslEnv }

$metadata = [ordered]@{
  wslDistribution=$WslDistribution; chrootRoot=$ChrootRoot; linuxRunRoot=$linuxRunRoot
  wslWindowsMount=$WslWindowsMount
  hostAddress=$HostAddress; port=$Port; clientRelayPort=$ClientRelayPort
  mainClass='cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication'
}
[IO.File]::WriteAllText($metadataFile, ($metadata | ConvertTo-Json -Compress) + "`n", [Text.UTF8Encoding]::new($false))
@{
  status='starting'; origin="http://${HostAddress}:$Port"; clientWsUrl="ws://127.0.0.1:$ClientRelayPort/ws/agent/channel"
  linuxReadyFile="$linuxRunRoot/io/ready.json"; linuxResultFile="$linuxRunRoot/io/result.json"
  linuxTokenFile="$linuxRunRoot/io/admin.jwt"; launcherMetadata=$metadataFile
} | ConvertTo-Json -Compress
