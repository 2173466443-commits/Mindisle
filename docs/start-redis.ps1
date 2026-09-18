# docs/start-redis.ps1 -- start the MindIsle dev-only Redis 7.2.16 (portable, E drive)
# Why a script: the portable build does NOT register a service, so it must be
# relaunched after every reboot. Run it before starting the backend, or set
# mindisle.cache.mode=local and the app falls back to Caffeine.
#
# Gotcha (verified 2026-09-18): this is an msys2 build. Passing an absolute
# Windows config path makes Redis try "/E:\dir\file" and it dies with
# "Fatal error, can't open config file". Also, Redis splits config lines on
# spaces, so a "dir" value containing a space is silently truncated.
# Fix used here: launch with -WorkingDirectory and a relative config + dir.
$ErrorActionPreference='Stop'
$home7 = 'E:\codex workspace\_tools\Redis-7.2.16-Windows-x64-msys2'
if(-not (Test-Path "$home7\redis-server.exe")){ throw "Redis portable build not found at $home7 (see 制作步骤文档 §2.1 task 0.4)" }

$existing = Get-NetTCPConnection -LocalPort 6379 -State Listen -EA SilentlyContinue
if($existing){ "redis already listening on 6379 (pid $($existing[0].OwningProcess)) - nothing to do"; exit 0 }

New-Item -ItemType Directory -Force -Path "$home7\data" | Out-Null
$conf = @('port 6379','bind 127.0.0.1','protected-mode no','daemonize no','dir data',
          'save 900 1','appendonly no','maxmemory-policy allkeys-lru',
          '# dev-only cache for MindIsle (009). localhost only, no persistence of record.')
[IO.File]::WriteAllLines("$home7\mindisle.conf",$conf,(New-Object System.Text.UTF8Encoding($false)))

$p = Start-Process -FilePath "$home7\redis-server.exe" -ArgumentList 'mindisle.conf' `
     -WorkingDirectory $home7 -WindowStyle Hidden -PassThru
$pong=$false
foreach($i in 1..15){ Start-Sleep -Milliseconds 600
  $r = (& "$home7\redis-cli.exe" -h 127.0.0.1 -p 6379 ping 2>&1)
  if("$r".Trim() -eq 'PONG'){ $pong=$true; break } }
if(-not $pong){ throw "redis-server started (pid $($p.Id)) but never answered PING - check $home7\data" }
"redis 7.2.16 UP  pid=$($p.Id)  PING=PONG  dir=$home7\data"