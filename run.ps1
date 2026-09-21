param(
    [ValidateSet('server', 'client', 'test', 'build')]
    [string]$Mode = 'client',
    [ValidateRange(1, 65535)]
    [int]$Port = 5000
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Uu tien JAVA_HOME, sau do PATH va cac thu muc cai JDK pho bien.
$jdkBin = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin' }
$compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($compiler) { $candidates += Split-Path $compiler.Source }
$roots = @(
    "$env:ProgramFiles\Java",
    "$env:ProgramFiles\Eclipse Adoptium",
    "$env:ProgramFiles\Microsoft",
    "$env:USERPROFILE\.jdks"
)
foreach ($root in $roots) {
    if (Test-Path -LiteralPath $root) {
        foreach ($directory in (Get-ChildItem -LiteralPath $root -Directory)) {
            $candidates += Join-Path $directory.FullName 'bin'
        }
    }
}
foreach ($candidate in $candidates) {
    if ((Test-Path -LiteralPath (Join-Path $candidate 'javac.exe')) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'java.exe'))) {
        $jdkBin = $candidate
        break
    }
}
if (-not $jdkBin) { throw 'Khong tim thay JDK. Hay cai JDK 11 tro len va dat JAVA_HOME.' }

New-Item -ItemType Directory -Path 'out' -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath 'src' -Filter '*.java' | ForEach-Object { $_.FullName })
if ($Mode -eq 'test') { $sources += Join-Path $PSScriptRoot 'tests\ChatIntegrationTest.java' }
Write-Host "Bien dich bang $jdkBin"
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 --release 11 -d out @sources
if ($LASTEXITCODE -ne 0) { throw 'Bien dich that bai.' }

switch ($Mode) {
    'server' { & (Join-Path $jdkBin 'java.exe') -cp out ChatServer $Port }
    'client' { & (Join-Path $jdkBin 'java.exe') -cp out ChatClient }
    'test'   { & (Join-Path $jdkBin 'java.exe') -cp out ChatIntegrationTest }
    'build'  { Write-Host 'Bien dich thanh cong.' }
}
if ($LASTEXITCODE -ne 0) { throw "Chuong trinh ket thuc voi ma loi $LASTEXITCODE." }
