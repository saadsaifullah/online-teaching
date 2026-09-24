$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'gradle/wrapper/gradle-wrapper.jar'
$download = "$jar.download"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $download
$expected = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
if ((Get-FileHash -Algorithm SHA256 $download).Hash.ToLowerInvariant() -ne $expected) {
    Remove-Item $download
    throw 'Gradle wrapper checksum mismatch'
}
Move-Item -Force $download $jar
