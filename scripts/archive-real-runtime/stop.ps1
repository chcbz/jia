param([Parameter(Mandatory=$true)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$run = [IO.Path]::GetFullPath($RunRoot)
$metadataFile = Join-Path $run 'control\launcher.json'
if (-not (Test-Path -LiteralPath $metadataFile)) { throw 'No fixture launcher metadata' }
$metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
foreach ($name in 'wslDistribution','chrootRoot','linuxRunRoot','wslWindowsMount','mainClass') {
  if ([string]::IsNullOrWhiteSpace($metadata.$name)) { throw "Invalid launcher metadata: $name" }
}
if ($metadata.mainClass -ne 'cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication') {
  throw 'Launcher metadata main class is not the fixture'
}
$stopFile = Join-Path $run 'control\stop.sh'
$root = [string]$metadata.chrootRoot
$linuxRun = [string]$metadata.linuxRunRoot
if ([string]$metadata.wslDistribution -notmatch '^[A-Za-z0-9._-]{1,64}$' -or
    $root -notmatch '^/[A-Za-z0-9._/-]+$' -or
    $linuxRun -notmatch '^/[A-Za-z0-9._/-]+$' -or
    [string]$metadata.wslWindowsMount -notmatch '^/[A-Za-z0-9._/-]+$') {
  throw 'Launcher metadata contains an unsafe shell value'
}
$stopTemplate = @'
#!/bin/sh
set -eu
umask 077
root='__CHROOT__'
run='__RUN__'
process="$root$run/io/process.json"
[ -f "$process" ] || { echo 'No fixture process metadata' >&2; exit 1; }
java_pid=$(chroot "$root" /usr/bin/node -p "require('$run/io/process.json').javaPid")
relay_pid=$(chroot "$root" /usr/bin/node -p "require('$run/io/process.json').relayPid")
case "$java_pid" in ''|*[!0-9]*) echo 'Invalid Java PID' >&2; exit 1;; esac
case "$relay_pid" in ''|*[!0-9]*) echo 'Invalid relay PID' >&2; exit 1;; esac
if [ -r "/proc/$java_pid/cmdline" ]; then
  java_cmd=$(tr '\000' ' ' < "/proc/$java_pid/cmdline")
  case "$java_cmd" in *cn.jia.fixture.archive.ArchiveRealRuntimeFixtureApplication*) kill -TERM "$java_pid" ;; *) echo 'Refusing to stop unverified Java process' >&2; exit 1;; esac
fi
if [ -r "/proc/$relay_pid/cmdline" ]; then
  relay_cmd=$(tr '\000' ' ' < "/proc/$relay_pid/cmdline")
  case "$relay_cmd" in *"$run/tcp-relay.mjs"*) kill -TERM "$relay_pid" ;; *) echo 'Refusing to stop unverified relay process' >&2; exit 1;; esac
fi
for ignored in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  [ ! -e "/proc/$java_pid" ] && [ ! -e "/proc/$relay_pid" ] && break
  sleep 1
done
[ ! -e "/proc/$java_pid" ] || { echo 'Fixture Java did not stop within 15 seconds' >&2; exit 1; }
[ ! -e "/proc/$relay_pid" ] || { echo 'Fixture relay did not stop within 15 seconds' >&2; exit 1; }
rm -f "$process"
echo STOPPED
'@
$stop = $stopTemplate.Replace('__CHROOT__', $root).Replace('__RUN__', $linuxRun)
[IO.File]::WriteAllText($stopFile, $stop, [Text.UTF8Encoding]::new($false))
$full = [IO.Path]::GetFullPath($stopFile)
if ($full -notmatch '^([A-Za-z]):\\(.*)$') { throw 'RunRoot must be a drive-rooted Windows path' }
$wslStop = "$([string]$metadata.wslWindowsMount.TrimEnd('/'))/$($Matches[1].ToLowerInvariant())/$($Matches[2].Replace('\','/'))"
& wsl.exe -d ([string]$metadata.wslDistribution) -- /bin/sh $wslStop
if ($LASTEXITCODE -ne 0) { throw 'Verified fixture stop failed' }
Remove-Item -LiteralPath $metadataFile -Force
@{status='stopped';linuxRunRoot=$linuxRun} | ConvertTo-Json -Compress
