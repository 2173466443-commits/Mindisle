# docs/init-db.ps1 -- MindIsle (009) one-shot database bootstrap
# usage:  powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\init-db.ps1
#         powershell ... -File .\docs\init-db.ps1 -AsRoot    # if the least-privilege grants turn out to be not enough
#
# What it does, in order:
#   1) sql/00_create_db_and_user.sql             as root      -> CREATE DATABASE + app user + grants
#   2) sql/01..08 + 09_seed.sql + 10_index.sql   as mindisle  -> 31 tables, seed data, indexes
#   3) verification                              as mindisle  -> base-table count must be 31
#
# Security rules (do NOT weaken them when editing this file):
#   * The MySQL root password is typed interactively, held in a SecureString, never stored in a file or in git.
#   * Passwords reach mysql.exe through a temporary --defaults-file, NOT through --password=..., because a
#     command-line password is visible to every other process (WMI / Task Manager) and makes mysql print
#     "Using a password on the command line interface can be insecure".
#   * Every temporary file is deleted in the finally block, including the SQL copy holding the app password.
#   * All DDL is CREATE TABLE IF NOT EXISTS and every seed row is INSERT IGNORE, so re-running is safe.

[CmdletBinding()]
param(
  [string]$MysqlExe = '',
  [switch]$AsRoot
)

$ErrorActionPreference = 'Stop'

# ---------- 0. locate mysql.exe ----------
if (-not $MysqlExe) {
  $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
  if ($cmd) { $MysqlExe = $cmd.Source }
  else { $MysqlExe = 'C:\Users\Drbrain\Doubao\chats\2026-07-27\new-chat\mysql\mysql-9.7.1-winx64\bin\mysql.exe' }
}
if (-not (Test-Path -LiteralPath $MysqlExe)) { throw 'mysql.exe not found: ' + $MysqlExe + '  (pass -MysqlExe <full path>)' }
'mysql client : ' + $MysqlExe

# ---------- 1. collect secrets ----------
function Unprotect-SecureString([System.Security.SecureString]$s) {
  return [Net.NetworkCredential]::new('', $s).Password
}
$rootPwd = Unprotect-SecureString (Read-Host 'MySQL ROOT password' -AsSecureString)
$appPwd  = Unprotect-SecureString (Read-Host 'new password for app user mindisle' -AsSecureString)
if ([string]::IsNullOrWhiteSpace($rootPwd)) { throw 'root password is empty' }
if ($appPwd.Length -lt 8) { throw 'app password must be at least 8 characters' }
# the app password is inlined into a single-quoted SQL literal inside 00_create_db_and_user.sql
if ($appPwd -match "['\\]") { throw 'app password must not contain a single quote or a backslash (SQL literal escaping)' }
if ($appPwd -match '\s')     { throw 'app password must not contain whitespace' }

# ---------- 2. scratch dir (workspace rule: caches and temp files live under E:\codex workspace\_cache) ----------
$projRoot = Split-Path -Parent $PSScriptRoot
$sqlDir   = Join-Path $projRoot 'sql'
$tmpDir   = 'E:\codex workspace\_cache\mindisle-dbtmp'
if (-not (Test-Path -LiteralPath $tmpDir)) {
  try { New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null } catch { $tmpDir = '' }
}
if (-not $tmpDir) {
  $tmpDir = Join-Path $PSScriptRoot '.tmp'
  New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
}
'scratch dir  : ' + $tmpDir

# ---------- 3. helpers ----------
function New-CredentialFile {
  param([string]$User, [string]$Password)
  $f = Join-Path $tmpDir ('my_' + [guid]::NewGuid().ToString('N') + '.cnf')
  # host/protocol pin the client to TCP 127.0.0.1:3306, otherwise a Windows client may try
  # named pipe / shared memory first and fail with a confusing error even though 3306 is listening.
  $lines = @('[client]', ('user=' + $User), ('password=' + $Password), 'host=127.0.0.1', 'protocol=tcp')
  [IO.File]::WriteAllLines($f, $lines, (New-Object Text.UTF8Encoding($false)))
  return $f
}

function Write-TempSql {
  param([string]$Text)
  $f = Join-Path $tmpDir ('q_' + [guid]::NewGuid().ToString('N') + '.sql')
  [IO.File]::WriteAllText($f, $Text, (New-Object Text.UTF8Encoding($false)))
  return $f
}

function Invoke-MySqlFile {
  param([string]$Cnf, [string]$Db, [string]$SqlPath, [string]$Label)
  $outF = Join-Path $tmpDir ('o_' + [guid]::NewGuid().ToString('N') + '.txt')
  $errF = Join-Path $tmpDir ('e_' + [guid]::NewGuid().ToString('N') + '.txt')
  # ONE pre-quoted argument string: Start-Process passes -ArgumentList string values verbatim to
  # CreateProcess, so the quoted defaults-file path survives the space inside "codex workspace".
  # An argument ARRAY does not give that guarantee (measured: it splits on spaces).
  # mysql.exe reads an option file ONLY from an explicit --defaults-extra-file=, and that option
  # must be the FIRST one on the command line. Passing the path as a bare positional argument makes
  # mysql treat it as the database name and log in as the OS user with no password -> ERROR 1045
  # "Access denied for user 'ODBC'@'localhost' (using password: NO)". Found by actually running the
  # script on 2026-09-20; until then this file had never been executed end to end.
  $argLine = '--defaults-extra-file="' + $Cnf + '" --no-beep --default-character-set=utf8mb4 --table'
  if ($Db) { $argLine = $argLine + ' --database=' + $Db }
  $p = Start-Process -FilePath $MysqlExe -ArgumentList $argLine -NoNewWindow -Wait -PassThru -RedirectStandardInput $SqlPath -RedirectStandardOutput $outF -RedirectStandardError $errF
  $code = $p.ExitCode
  $outTxt = ''
  if (Test-Path -LiteralPath $outF) { $outTxt = [IO.File]::ReadAllText($outF) }
  $errTxt = ''
  if (Test-Path -LiteralPath $errF) { $errTxt = [IO.File]::ReadAllText($errF) }
  Remove-Item -LiteralPath $outF, $errF -Force -ErrorAction SilentlyContinue
  foreach ($l in ($outTxt -split '\r?\n')) { if ($l.Trim()) { '    | ' + $l } }
  foreach ($l in ($errTxt -split '\r?\n')) { if ($l.Trim()) { '    ! ' + $l } }
  if ($code -ne 0) { throw ($Label + ' failed, mysql.exe exit code ' + $code) }
  return $outTxt
}

$ddlFiles = @('01_account.sql','02_ai.sql','03_emotion.sql','04_community.sql','05_recommend.sql',
              '06_pm.sql','07_audit.sql','08_config.sql','09_seed.sql','10_index.sql')
$madeFiles = @()
try {
  foreach ($name in (,'00_create_db_and_user.sql') + $ddlFiles) {
    $path = Join-Path $sqlDir $name
    if (-not (Test-Path -LiteralPath $path)) { throw 'missing sql file: ' + $path }
  }

  # ---- 4a. database + least-privilege account, as root
  $rootCnf = New-CredentialFile -User 'root' -Password $rootPwd
  $madeFiles += $rootCnf
  $bootSql  = Join-Path $sqlDir '00_create_db_and_user.sql'
  $bootText = [IO.File]::ReadAllText($bootSql).Replace('__APP_USER_PASSWORD__', $appPwd)
  $bootTmp  = Write-TempSql -Text $bootText
  $madeFiles += $bootTmp
  ''
  '=== [1/3] 00_create_db_and_user.sql (as root) ==='
  Invoke-MySqlFile -Cnf $rootCnf -Db '' -SqlPath $bootTmp -Label '00_create_db_and_user.sql' | Out-Null

  # ---- 4b. DDL + seed + index. Default owner is the APP USER, which doubles as a least-privilege proof:
  #         a missing grant surfaces here instead of three months later in production. -AsRoot overrides.
  $ddlCnf = $rootCnf
  $ddlUser = 'root'
  if (-not $AsRoot) {
    $appCnf = New-CredentialFile -User 'mindisle' -Password $appPwd
    $madeFiles += $appCnf
    $ddlCnf = $appCnf
    $ddlUser = 'mindisle'
  }
  '=== [2/3] 01..10 schema + seed + index (as ' + $ddlUser + ') ==='
  foreach ($name in $ddlFiles) {
    '--- ' + $name
    Invoke-MySqlFile -Cnf $ddlCnf -Db 'mindisle' -SqlPath (Join-Path $sqlDir $name) -Label $name | Out-Null
  }

  # ---- 4c. verify, same account the application will use
  '=== [3/3] verify as ' + $ddlUser + ' ==='
  $vText = "SELECT COUNT(*) AS base_tables FROM information_schema.tables WHERE table_schema='mindisle' AND table_type='BASE TABLE'; SELECT VERSION() AS mysql_version, @@character_set_database AS charset, @@collation_database AS collation;"
  $vSql = Write-TempSql -Text $vText
  $madeFiles += $vSql
  $vRaw = Invoke-MySqlFile -Cnf $ddlCnf -Db '' -SqlPath $vSql -Label 'verification'
  # Invoke-MySqlFile echoes its display lines into the pipeline and THEN returns the raw stdout,
  # so the real text is the LAST element. Measured 2026-09-20.
  $vText = @(,$vRaw)[-1]
  # `$arr -notmatch 'x'` returns the NON-matching elements (a non-empty array is truthy); it does NOT mean
  # "nothing matched". With the display lines in the array that check threw even though the database really
  # had 31 tables - it made a SUCCESSFUL run look like a failure. Match the count cell itself instead.
  if ($vText -notmatch '\|\s*31\s*\|') {
    throw ('expected 31 base tables in mindisle, got: ' + ($vText -replace '\s+', ' '))
  }

  ''
  'DATABASE READY: mindisle has 31 tables.'
  'LAST MANUAL STEP -- put the app password you just typed into .env as DB_PASSWORD, then restart the backend:'
  '  notepad "' + (Join-Path $projRoot '.env') + '"'
  '    DB_PASSWORD=<the password you typed above>      (this file is git-ignored, never commit it)'
  '  cd backend; mvn -B spring-boot:run'
} finally {
  foreach ($f in $madeFiles) { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
  $rootPwd = $null
  $appPwd = $null
  'temporary credential files removed.'
}
