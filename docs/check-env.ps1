# docs/check-env.ps1 -- MindIsle (009) stage-0 environment self-check
# run:  powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\check-env.ps1
# NOTE 1: ASCII-only on purpose -> no UTF-8 BOM needed, survives any ANSI codepage.
# NOTE 2: every argument passed to chk() MUST be wrapped in parentheses.
#         `chk 'x' [bool](Get-Command x) 'hint'` silently binds the string
#         '[bool]' to $ok, so the check reports [OK] even when the tool is
#         missing. Verified against a shell where mvn is absent.
$ErrorActionPreference='SilentlyContinue'
$script:fail=0
function chk($name,$ok,$hint){
  $tag = if($ok){'[OK]'}else{'[!!]'}
  if(-not $ok){ $script:fail++ }
  "{0,-5} {1,-20} {2,-46} {3}" -f $tag,$name,"$ok","$hint"
}
"=== MindIsle env check $(Get-Date -Format 'yyyy-MM-dd HH:mm') ==="
"{0,-5} {1,-20} {2,-46} {3}" -f 'tag','name','bound-as-$ok','hint'

# java prints its banner to stderr; merge it at the cmd level, not via PS redirection
$jav = (cmd /c 'java -version 2>&1') | Select-Object -First 1
if(-not $jav -and $env:JAVA_HOME){ $jav = (cmd /c "`"$env:JAVA_HOME\bin\java.exe`" -version 2>&1") | Select-Object -First 1 }
$javNum = if($jav -match '"([^"]+)"'){ $matches[1] } else { 'n/a' }
chk 'java'   ([bool]($jav -match 'version')) ("JDK 17+ required; found $javNum")
chk 'mvn'    ([bool](Get-Command mvn))       'apache-maven 3.9.16 (from _tools)'
chk 'node'   ([bool](Get-Command node))      'v20.19+ required for Vite 8'
chk 'npm'    ([bool](Get-Command npm))       'npm 10+'
chk 'git'    ([bool](Get-Command git))       'git 2.x'
chk 'python' ([bool](Get-Command python))    '3.10+ for experiment scripts'

$up3306 = (Test-NetConnection 127.0.0.1 -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue)
chk 'mysql:3306' ([bool]$up3306) 'start Windows service "MySQL"'
$up6379 = (Test-NetConnection 127.0.0.1 -Port 6379 -InformationLevel Quiet -WarningAction SilentlyContinue)
chk 'redis:6379' ([bool]$up6379) 'optional; else set mindisle.cache.mode=local'

chk 'port:8080-free' (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -EA SilentlyContinue)) 'backend dev port'
chk 'port:5173-free' (-not (Get-NetTCPConnection -LocalPort 5173 -State Listen -EA SilentlyContinue)) 'vite user app'
chk 'port:5174-free' (-not (Get-NetTCPConnection -LocalPort 5174 -State Listen -EA SilentlyContinue)) 'vite admin app'

$proj = Split-Path -Parent $PSScriptRoot
chk 'gitignore'       (Test-Path (Join-Path $proj '.gitignore')) 'must exist before first commit'
chk 'maven-repo-on-E' (Test-Path 'E:\codex workspace\_cache\m2\repository') 'settings.xml localRepository'
chk 'npm-cache-on-E'  ((npm config get cache) -like 'E:*') 'npm config set cache'
chk 'pip-cache-on-E'  (((python -m pip config list 2>&1) -match 'E:.+_cache.pipeline|_cache..pip')) 'pip config set global.cache-dir'
chk 'redis-on-E'      (Test-Path 'E:\codex workspace\_tools\Redis-7.2.16-Windows-x64-msys2\redis-server.exe') 'portable build, never C drive'

"--- file encoding red line ---"
foreach($f in @((Join-Path $proj '.env'),
                (Join-Path $proj 'backend\src\main\resources\application.yml'),
                (Join-Path $proj 'frontend\.env.local'))){
  if(Test-Path $f){
    $b=[IO.File]::ReadAllBytes((Resolve-Path $f))
    chk ('bom-free:'+($f.Substring($f.LastIndexOf('\')+1))) ($b.Length -lt 3 -or $b[0] -ne 0xEF) 'strip BOM (yml/env must be UTF-8 no BOM)'
  }
}
Get-ChildItem -Path $proj -Recurse -Include '*.ps1' -EA SilentlyContinue | ForEach-Object {
  $b=[IO.File]::ReadAllBytes($_.FullName)
  $txt=[Text.Encoding]::UTF8.GetString($b)
  if($txt -match '[^\x00-\x7F]'){
    chk ('ps1-bom-needed:'+($_.Name)) ($b.Length -ge 3 -and $b[0] -eq 0xEF) 'CJK inside .ps1 requires UTF-8 BOM'
  }
}
"--- secret scan (must be 0 hits) ---"
$hits = Get-ChildItem -Path $proj -Recurse -Include '*.yml','*.yaml','*.properties','*.java','*.ts','*.js','*.vue' -EA SilentlyContinue |
  Select-String -Pattern 'sk-[A-Za-z0-9]{16,}|password\s*=\s*[''"]?(?!\$\{|changeme|CHANGEME)[A-Za-z0-9]{6,}' -EA SilentlyContinue
chk 'no-inline-secrets' ((@($hits).Count -eq 0)) ("suspected lines: " + (@($hits).Count))
"=== result: $script:fail failed ==="
exit ([Math]::Min($script:fail,9))