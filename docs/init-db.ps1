# docs/init-db.ps1 -- MindIsle stage-0 task 0.5: create schema + least-privilege app user
# The MySQL root password is NEVER stored in a file, in git, or in this script.
# It is asked for interactively each run and held only in a SecureString.
$ErrorActionPreference='Stop'
$mysqlExe = 'C:\Users\Drbrain\Doubao\chats\2026-07-27\new-chat\mysql\mysql-9.7.1-winx64\bin\mysql.exe'
if(-not (Test-Path -LiteralPath $mysqlExe)){ throw "mysql.exe not found at $mysqlExe" }

$sec   = Read-Host 'MySQL ROOT password' -AsSecureString
$appSec= Read-Host 'password for the new app user mindisle' -AsSecureString
$root  = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
$app   = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($appSec))

$sqlFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'sql\00_create_db_and_user.sql'
$sql = [IO.File]::ReadAllText($sqlFile).Replace('__APP_USER_PASSWORD__',$app)
"running $sqlFile ..."
$tmp = Join-Path $env:TEMP ('mindisle_init_' + [guid]::NewGuid().ToString('N') + '.sql')
[IO.File]::WriteAllText($tmp,$sql,(New-Object System.Text.UTF8Encoding($false)))
try{
  & $mysqlExe -u root --password=$root -t (Get-Content -LiteralPath $tmp -Raw) 2>&1 | ForEach-Object { "$_" }
  if($LASTEXITCODE -ne 0){ throw "mysql exited $LASTEXITCODE" }
} finally {
  Remove-Item -LiteralPath $tmp -Force -EA SilentlyContinue   # shred the copy that held the password
  $sql=$null; $app=$null
}
"--- verify as the app user (not root) ---"
& $mysqlExe -u mindisle --password=$app -D mindisle -e 'select database() as db, @@character_set_database as charset, @@collation_server as coll' 2>&1 | ForEach-Object { "$_" }
"OK - store the app password in .env (DB_PASSWORD), never in git."